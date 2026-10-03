package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

class SyncFilesTool : AbstractMcpTool() {

    override val requiresPsiSync: Boolean = false

    override val name = ToolNames.SYNC_FILES

    override val description = """
        Force the IDE to synchronize its virtual file system and PSI cache with external file changes. Use when files were created, modified, or deleted outside the IDE (e.g., by coding agents) and other IDE tools report stale results or miss references in recently changed files.
        call it on-demand only when needed.
        Parameters: paths (optional array of relative or in-project absolute file/directory paths to sync; if omitted, syncs entire project), project_path (optional).
        Example: {} or {"paths": ["src/main/java/com/example/NewFile.java", "src/main/java/com/example/ModifiedFile.java"]}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .property("paths", buildJsonObject {
            put("type", "array")
            putJsonObject("items") {
                put("type", "string")
            }
            put(
                "description",
                "Paths relative to the selected project/content root, or absolute paths inside any project/content root. Rejects traversal, symlink escapes and unknown missing paths. Deleted paths known to VFS refresh their nearest existing parent. Relative paths are tried against the project base then module content roots when project_path is omitted or selects the project base; a selected content root confines relative paths to that root. Omit to sync the entire selected root."
            )
        })
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        if (project.basePath == null) {
            return createErrorResult("Project base path is not available.")
        }

        val requestedPaths = arguments["paths"]?.jsonArray?.map { it.jsonPrimitive.content }
        val projectPathArg = arguments["project_path"]?.jsonPrimitive?.contentOrNull
        val allowedRoots = allowedRoots(project)
        if (allowedRoots.isEmpty()) {
            return createErrorResult("No project base/content roots can be resolved safely for synchronization.")
        }
        val projectBaseRoot = syncRootOrNull(requireNotNull(project.basePath))
        val selectedRoot = projectPathArg?.let { resolveEffectiveRoot(allowedRoots, it) }
        if (projectPathArg != null && selectedRoot == null) {
            return createErrorResult(
                "The selected project_path is not inside the project base path or one of its content roots."
            )
        }
        val relativeCandidateRoots = when {
            selectedRoot == null -> allowedRoots
            selectedRoot.path == projectBaseRoot?.path ->
                listOf(selectedRoot) + allowedRoots.filterNot { it.path == selectedRoot.path }
            else -> listOf(selectedRoot)
        }
        val absoluteCandidateRoots = selectedRoot?.let { selected ->
            listOf(selected) + allowedRoots.filterNot { it.path == selected.path }
        } ?: allowedRoots

        val syncedPaths: List<String>
        val refreshedRoots: List<String>
        val deletedPaths: List<String>
        val syncedAll: Boolean

        if (requestedPaths != null && requestedPaths.isNotEmpty()) {
            // Validate and resolve the complete batch before touching the VFS. A bad path must not
            // leave the call half-applied.
            val targets = mutableListOf<SyncTarget>()
            val validationErrors = mutableListOf<String>()
            for (requestedPath in requestedPaths) {
                try {
                    targets += resolveTarget(relativeCandidateRoots, absoluteCandidateRoots, requestedPath)
                } catch (e: IllegalArgumentException) {
                    validationErrors += "'$requestedPath': ${e.message ?: "Invalid sync path."}"
                }
            }

            val knownRoots = mutableListOf<Pair<SyncTarget, RefreshRoot>>()
            for (target in targets) {
                val knownRoot = findVfsRefreshRoot(target.root.path, target.diskRefreshPath)
                if (knownRoot == null) {
                    validationErrors +=
                        "'${target.requestedPath}': Could not find an existing VFS parent inside the selected project root."
                } else {
                    knownRoots += target to knownRoot
                }
            }
            if (validationErrors.isNotEmpty()) {
                return createErrorResult(
                    "Invalid sync paths:\n" + validationErrors.joinToString("\n") { "- $it" }
                )
            }
            val discoveryRoots = mutableListOf<RefreshRoot>()
            val shallowRefreshedPaths = mutableSetOf<Path>()
            val refreshTargets = minimalRefreshRoots(knownRoots.map { (target, knownRoot) ->
                var current = knownRoot
                while (current.path != target.diskRefreshPath) {
                    ProgressManager.checkCanceled()
                    // Discover only the requested path components. Recursing from the nearest
                    // cached ancestor could refresh the whole project for one newly created file.
                    if (shallowRefreshedPaths.add(current.path)) {
                        VfsUtil.markDirtyAndRefresh(false, false, true, current.virtualFile)
                        discoveryRoots += current.copy(recursive = false)
                    }
                    val nextPath = current.path.resolve(current.path.relativize(target.diskRefreshPath).getName(0))
                    val nextFile = LocalFileSystem.getInstance().findFileByPath(nextPath.toString())
                        ?: return createErrorResult("Path disappeared while synchronizing '${target.requestedPath}'.")
                    current = RefreshRoot(nextPath, nextFile)
                }
                // Deletion only needs a shallow parent refresh; VFS invalidates the removed
                // child's whole subtree. Explicit directory targets still receive a full refresh.
                current.copy(recursive = target.existed)
            })

            for ((recursive, roots) in refreshTargets.groupBy { it.recursive }) {
                VfsUtil.markDirtyAndRefresh(
                    false,
                    recursive,
                    true,
                    *roots.map { it.virtualFile }.toTypedArray()
                )
            }

            syncedPaths = targets.map { it.displayPath }
            refreshedRoots = minimalRefreshRoots(discoveryRoots + refreshTargets)
                .map { systemIndependentPath(it.path) }
            deletedPaths = targets.filterNot { it.existed }.map { it.displayPath }
            syncedAll = false
        } else {
            val effectiveRoot = selectedRoot ?: projectBaseRoot
                ?: return createErrorResult("The project base path cannot be resolved safely for a full synchronization.")
            val projectDir = LocalFileSystem.getInstance().findFileByPath(effectiveRoot.path.toString())
                ?: return createErrorResult("Selected project root is not available in the IDE VFS: ${effectiveRoot.path}")
            VfsUtil.markDirtyAndRefresh(false, true, true, projectDir)
            syncedPaths = listOf(systemIndependentPath(effectiveRoot.path))
            refreshedRoots = listOf(systemIndependentPath(effectiveRoot.path))
            deletedPaths = emptyList()
            syncedAll = true
        }

        commitDocuments(project)

        val message = when {
            syncedAll -> "Synchronized entire project."
            deletedPaths.isNotEmpty() ->
                "Synchronized ${syncedPaths.size} path(s), including ${deletedPaths.size} deleted path(s) via existing parent directories."
            else -> "Synchronized ${syncedPaths.size} path(s)."
        }

        return createJsonResult(SyncFilesResult(
            syncedPaths = syncedPaths,
            syncedAll = syncedAll,
            message = message,
            refreshedRoots = refreshedRoots,
            deletedPaths = deletedPaths
        ))
    }

    private fun allowedRoots(project: Project): List<SyncRoot> =
        (listOfNotNull(project.basePath) + ProjectUtils.getModuleContentRoots(project))
            .mapNotNull(::syncRootOrNull)
            .distinctBy { it.path }

    /** Resolve the routing hint to the most specific project/content root that contains it. */
    private fun resolveEffectiveRoot(allowedRoots: List<SyncRoot>, projectPathArg: String): SyncRoot? {
        val requestedRoot = syncRootOrNull(projectPathArg) ?: return null

        return allowedRoots
            .filter { requestedRoot.realPath.startsWith(it.realPath) }
            .maxWithOrNull(
                compareBy<SyncRoot> { it.realPath.nameCount }
                    // Several registered aliases may have the same canonical root. Refresh
                    // the VFS tree selected by the caller, not another alias of those files.
                    .thenBy { if (requestedRoot.path.startsWith(it.path)) it.path.nameCount else -1 }
            )
    }

    private fun resolveTarget(
        relativeCandidateRoots: List<SyncRoot>,
        absoluteCandidateRoots: List<SyncRoot>,
        requestedPath: String
    ): SyncTarget {
        val parsedPath = parseSafePath(requestedPath)
        val rootedTargets = if (parsedPath.isAbsolute) {
            val requestedRealPath = realPathOrNull(parsedPath.path)
            absoluteCandidateRoots.mapNotNull { root ->
                when {
                    parsedPath.path.startsWith(root.path) -> root to parsedPath.path
                    requestedRealPath?.startsWith(root.realPath) == true -> {
                        val relativePath = root.realPath.relativize(requestedRealPath)
                        root to root.path.resolve(relativePath).normalize()
                    }
                    else -> null
                }
            }
        } else {
            relativeCandidateRoots.map { root -> root to root.path.resolve(parsedPath.path).normalize() }
        }
        if (rootedTargets.isEmpty()) {
            throw IllegalArgumentException("Absolute path is outside the project base/content roots")
        }

        val failures = mutableListOf<IllegalArgumentException>()
        for ((root, targetPath) in rootedTargets) {
            try {
                return resolveTarget(root, requestedPath, targetPath, parsedPath.isAbsolute)
            } catch (e: IllegalArgumentException) {
                failures += e
            }
        }
        throw failures.firstOrNull { it.message?.contains("symbolic link") == true }
            ?: failures.firstOrNull()
            ?: IllegalArgumentException("Not found")
    }

    private fun resolveTarget(
        effectiveRoot: SyncRoot,
        requestedPath: String,
        targetPath: Path,
        wasAbsolute: Boolean
    ): SyncTarget {
        if (!targetPath.startsWith(effectiveRoot.path)) {
            throw IllegalArgumentException("Path escapes the selected project root")
        }

        val existed = Files.exists(targetPath)
        val nearestExisting = nearestExistingPath(effectiveRoot.path, targetPath)
            ?: throw IllegalArgumentException("No existing parent inside the selected project root")
        val realExisting = try {
            nearestExisting.toRealPath()
        } catch (_: IOException) {
            throw IllegalArgumentException("Path cannot be resolved safely; it may contain a dangling symbolic link")
        } catch (_: SecurityException) {
            throw IllegalArgumentException("Path cannot be resolved safely; it may contain a dangling symbolic link")
        }
        if (!realExisting.startsWith(effectiveRoot.realPath)) {
            throw IllegalArgumentException("Path escapes the selected project root through a symbolic link")
        }
        if (!existed && LocalFileSystem.getInstance().findFileByPath(targetPath.toString()) == null) {
            throw IllegalArgumentException(
                "Not found on disk or in the IDE VFS; only a VFS-known missing path can be synchronized as a deletion"
            )
        }

        return SyncTarget(
            root = effectiveRoot,
            requestedPath = requestedPath,
            displayPath = if (wasAbsolute) {
                systemIndependentPath(targetPath)
            } else {
                relativeDisplayPath(effectiveRoot.path.relativize(targetPath))
            },
            diskRefreshPath = if (existed) targetPath else nearestExisting,
            existed = existed
        )
    }

    private fun parseSafePath(requestedPath: String): ParsedSyncPath {
        if (requestedPath.isBlank()) {
            throw IllegalArgumentException("Sync paths must not be blank.")
        }
        val path = try {
            Path.of(requestedPath)
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("Invalid sync path: $requestedPath")
        }
        val hasAbsoluteSyntax = path.isAbsolute || requestedPath.startsWith('/') ||
            requestedPath.startsWith('\\') || WINDOWS_DRIVE_PATH.matches(requestedPath)
        if (hasAbsoluteSyntax && !path.isAbsolute) {
            throw IllegalArgumentException("Absolute path syntax is not valid on this platform: $requestedPath")
        }
        if (!path.isAbsolute && requestedPath.split('/', '\\').any { it == ".." }) {
            throw IllegalArgumentException("Path traversal is not allowed in relative sync paths: $requestedPath")
        }
        return ParsedSyncPath(
            path = if (path.isAbsolute) path.toAbsolutePath().normalize() else path.normalize(),
            isAbsolute = path.isAbsolute
        )
    }

    private fun nearestExistingPath(effectiveRoot: Path, targetPath: Path): Path? {
        var candidate: Path? = targetPath
        while (candidate != null && (candidate == effectiveRoot || candidate.startsWith(effectiveRoot))) {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate
            }
            if (candidate == effectiveRoot) break
            candidate = candidate.parent
        }
        return null
    }

    private fun findVfsRefreshRoot(effectiveRoot: Path, diskRefreshPath: Path): RefreshRoot? {
        val localFileSystem = LocalFileSystem.getInstance()
        var candidate: Path? = diskRefreshPath
        while (candidate != null && (candidate == effectiveRoot || candidate.startsWith(effectiveRoot))) {
            val virtualFile = localFileSystem.findFileByPath(candidate.toString())
            if (virtualFile != null) {
                return RefreshRoot(candidate, virtualFile)
            }
            if (candidate == effectiveRoot) break
            candidate = candidate.parent
        }
        return null
    }

    /** Recursive refresh of an ancestor already covers all requested descendants. */
    private fun minimalRefreshRoots(roots: List<RefreshRoot>): List<RefreshRoot> {
        val selected = mutableListOf<RefreshRoot>()
        val combined = roots.groupBy { it.path }.values.map { duplicates ->
            duplicates.first().copy(recursive = duplicates.any { it.recursive })
        }
        for (candidate in combined.sortedBy { it.path.nameCount }) {
            if (selected.none { candidate.path == it.path || it.recursive && candidate.path.startsWith(it.path) }) {
                selected += candidate
            }
        }
        return selected
    }

    private fun relativeDisplayPath(path: Path): String = path.joinToString("/") { it.toString() }

    private fun systemIndependentPath(path: Path): String = path.toString().replace('\\', '/')

    private fun syncRootOrNull(path: String): SyncRoot? {
        return try {
            val absolutePath = Path.of(path).toAbsolutePath()
            val realPath = realPathOrNull(absolutePath) ?: return null
            SyncRoot(absolutePath.normalize(), realPath)
        } catch (_: InvalidPathException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * Resolves the surviving prefix so deleted paths keep canonical containment information.
     * A dangling symlink deliberately returns null instead of being treated as an ordinary
     * missing suffix.
     */
    private fun realPathOrNull(path: Path): Path? {
        val absolutePath = path.toAbsolutePath().normalize()
        var existing = absolutePath
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.parent ?: return null
        }
        return try {
            existing.toRealPath().resolve(existing.relativize(absolutePath)).normalize()
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    /** VFS keeps separate cached trees for symlink aliases; canonical paths only define containment. */
    private data class SyncRoot(
        val path: Path,
        val realPath: Path
    )

    private data class SyncTarget(
        val root: SyncRoot,
        val requestedPath: String,
        val displayPath: String,
        val diskRefreshPath: Path,
        val existed: Boolean
    )

    private data class ParsedSyncPath(
        val path: Path,
        val isAbsolute: Boolean
    )

    private data class RefreshRoot(
        val path: Path,
        val virtualFile: VirtualFile,
        val recursive: Boolean = true
    )

    private companion object {
        val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:.*")
    }
}
