package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SyncFilesResult
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

/**
 * Behavior coverage for `ide_sync_files`.
 *
 * This tool is enabled by default and previously had no execution coverage at all — only schema
 * and registration assertions. Its entire reason to exist is making externally-created files
 * visible to the IDE, so that is what these tests exercise: write a file behind the VFS's back,
 * then assert the tool surfaces it.
 */
class SyncFilesToolBehaviorTest : McpPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val rootPath = requireNotNull(project.basePath)
        Files.createDirectories(java.nio.file.Path.of(rootPath))
        requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(rootPath)).children
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun decode(text: String): SyncFilesResult = json.decodeFromString(text)

    fun testMisspelledPathDoesNotRefreshTheWholeProject() = runBlocking {
        writeProjectFile("unrelated-external/Visible.java", "class Visible {}")
        val parent = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/unrelated-external"))
        parent.children // Populate the VFS child cache before the external write.
        writeBehindVfs("unrelated-external/Hidden.java", "class Hidden {}")
        val hiddenPath = "${project.basePath}/unrelated-external/Hidden.java"
        assertNull("External file must start invisible", LocalFileSystem.getInstance().findFileByPath(hiddenPath))

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(JsonPrimitive("misspelled-directory/Missing.java")) })
        })

        assertTrue("An unknown missing path must be rejected", result.isFailure)
        assertTrue("The caller needs an actionable missing-path error", toolText(result).contains("Not found"))
        assertNull("A typo must not trigger a recursive project refresh", LocalFileSystem.getInstance().findFileByPath(hiddenPath))
    }

    /** Writes straight to disk, deliberately bypassing the VFS refresh in `writeProjectFile`. */
    private fun writeBehindVfs(relativePath: String, content: String) {
        val basePath = requireNotNull(project.basePath)
        val path = java.nio.file.Path.of(basePath, relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    /** The tool's entire reason to exist: a file created behind the IDE's back becomes visible. */
    fun testSyncMakesAnExternallyCreatedFileVisibleToTheVfs() = runBlocking {
        writeBehindVfs("synced/External.java", "public class External {}\n")
        assertNull(
            "Precondition: the file must be invisible to the VFS before syncing, " +
                "otherwise this test proves nothing",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )

        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed", result)
        assertNotNull(
            "After ide_sync_files the externally created file must be resolvable through the VFS",
            LocalFileSystem.getInstance().findFileByPath("${project.basePath}/synced/External.java")
        )
    }

    fun testSyncReportsTheExactPathsItSynchronized() = runBlocking {
        writeProjectFile("synced/A.java", "public class A {}\n")
        writeProjectFile("synced/B.java", "public class B {}\n")

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/A.java"))
                add(JsonPrimitive("synced/B.java"))
            })
        })

        assertToolSucceeded("sync_files should succeed", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/A.java", "synced/B.java"), payload.syncedPaths)
        assertEquals(
            listOf(
                Path.of(requireNotNull(project.basePath), "synced/A.java").toString(),
                Path.of(requireNotNull(project.basePath), "synced/B.java").toString()
            ),
            payload.refreshedRoots
        )
        assertTrue(payload.deletedPaths.isEmpty())
        assertFalse("An explicit path list is not a whole-project sync", payload.syncedAll)
        assertEquals("Synchronized 2 path(s).", payload.message)
    }

    fun testSyncWithoutPathsSynchronizesTheWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject { })

        assertToolSucceeded("sync_files should succeed with no arguments", result)
        val payload = decode(toolText(result))
        assertTrue("Omitting paths must sync the whole project", payload.syncedAll)
        assertEquals(listOf(project.basePath), payload.syncedPaths)
        assertEquals(listOf(project.basePath), payload.refreshedRoots)
        assertTrue(payload.deletedPaths.isEmpty())
        assertEquals("Synchronized entire project.", payload.message)
    }

    /**
     * A deleted path is refreshed through its nearest existing parent. This invalidates the stale
     * VFS entry while keeping the requested and actually-refreshed paths unambiguous in the result.
     */
    fun testSyncRefreshesDeletedPathThroughNearestExistingParent() = runBlocking {
        val deletedFile = writeProjectFile("synced/deleted/Old.java", "public class Old {}\n")
        val absolutePath = deletedFile.toString()
        val staleVirtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath(absolutePath))
        val stalePsiFile = requireNotNull(PsiManager.getInstance(project).findFile(staleVirtualFile))
        Files.delete(deletedFile)

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive("synced/deleted/Old.java"))
            })
        })

        assertToolSucceeded("A deleted target should be synchronized via its parent", result)
        val payload = decode(toolText(result))
        assertEquals(listOf("synced/deleted/Old.java"), payload.syncedPaths)
        assertEquals(listOf(deletedFile.parent.toString()), payload.refreshedRoots)
        assertEquals(listOf("synced/deleted/Old.java"), payload.deletedPaths)
        assertEquals(
            "Synchronized 1 path(s), including 1 deleted path(s) via existing parent directories.",
            payload.message
        )
        assertNull("The deleted file must be evicted from the VFS", LocalFileSystem.getInstance().findFileByPath(absolutePath))
        assertFalse("The deleted file's PSI must be invalidated", stalePsiFile.isValid)
    }

    fun testSyncWithEmptyPathListFallsBackToWholeProject() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { })
        })

        assertToolSucceeded("An empty path list should not error", result)
        val payload = decode(toolText(result))
        assertTrue("An empty list is treated as 'sync everything'", payload.syncedAll)
    }

    fun testSyncRejectsTraversalAndAcceptsAbsoluteInProjectPaths() = runBlocking {
        val traversal = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(JsonPrimitive("synced/../Outside.java")) })
        })
        assertToolFailed("Path traversal must be rejected", traversal)
        assertTrue(toolText(traversal), toolText(traversal).contains("traversal"))

        val absoluteFile = writeProjectFile("synced/Absolute.java", "class Absolute {}\n")
        val absolute = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(JsonPrimitive(absoluteFile.toString())) })
        })
        assertToolSucceeded("Absolute paths inside project roots must be accepted", absolute)
        assertEquals(listOf(absoluteFile.toString()), decode(toolText(absolute)).syncedPaths)

        val normalizedAbsolute = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive(absoluteFile.parent.resolve("unused/../Absolute.java").toString()))
            })
        })
        assertToolSucceeded("Normalized absolute paths inside project roots must be accepted", normalizedAbsolute)
        assertEquals(listOf(absoluteFile.toString()), decode(toolText(normalizedAbsolute)).syncedPaths)

        val outsideFile = Files.createTempFile("ide-sync-files-outside", ".java")
        try {
            val outside = SyncFilesTool().execute(project, buildJsonObject {
                put("paths", buildJsonArray { add(JsonPrimitive(outsideFile.toString())) })
            })
            assertToolFailed("Absolute paths outside project roots must be rejected", outside)
            assertTrue(toolText(outside), toolText(outside).contains("outside"))
        } finally {
            Files.deleteIfExists(outsideFile)
        }
    }

    fun testSyncReportsEveryInvalidPathBeforeRefreshing() = runBlocking {
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray {
                add(JsonPrimitive(""))
                add(JsonPrimitive("one/../escape"))
                add(JsonPrimitive("missing-batch/Unknown.java"))
            })
        })

        assertToolFailed("The complete invalid batch must be reported", result)
        val message = toolText(result)
        assertTrue(message, message.contains("''"))
        assertTrue(message, message.contains("one/../escape"))
        assertTrue(message, message.contains("missing-batch/Unknown.java"))
    }

    fun testSyncRejectsSymbolicLinkEscape() = runBlocking {
        val outsideDirectory = Files.createTempDirectory("ide-sync-files-outside")
        val link = java.nio.file.Path.of(requireNotNull(project.basePath), "outside-link")

        try {
            createSymbolicLinkOrSkip(link, outsideDirectory)
            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("paths", buildJsonArray { add(JsonPrimitive("outside-link/Deleted.java")) })
            })

            assertToolFailed("A symlink must not escape the selected project root", result)
            assertTrue(toolText(result).contains("symbolic link"))
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(outsideDirectory)
        }
    }

    fun testFullSyncRefreshesLoadedPsiThroughSelectedSymbolicLinkContentRoot() = runBlocking {
        val realRoot = Files.createTempDirectory("ide-sync-files-real-root").toRealPath()
        val aliasPath = Path.of(requireNotNull(project.basePath), "linked-module")
        val changedPath = realRoot.resolve("Changed.java")
        val deletedPath = realRoot.resolve("Old.java")
        val originalText = "class Changed { int before; }\n"
        val changedText = "class Changed { long externallyChanged; }\n"
        Files.writeString(changedPath, originalText)
        Files.writeString(deletedPath, "class Old {}\n")

        try {
            createSymbolicLinkOrSkip(aliasPath, realRoot)
            val localFileSystem = LocalFileSystem.getInstance()
            val aliasRoot = requireNotNull(localFileSystem.refreshAndFindFileByPath(aliasPath.toString()))
            PsiTestUtil.addContentRoot(module, aliasRoot)
            IndexingTestUtil.waitUntilIndexesAreReady(project)

            val aliasChanged = requireNotNull(aliasRoot.findChild("Changed.java"))
            val aliasDeleted = requireNotNull(aliasRoot.findChild("Old.java"))
            val canonicalChanged = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(changedPath))
            val canonicalDeleted = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(deletedPath))
            assertNotSame("The alias must have its own cached VFS file", canonicalChanged, aliasChanged)
            assertNotSame("The deleted alias must have its own cached VFS file", canonicalDeleted, aliasDeleted)
            assertEquals(aliasPath.resolve("Changed.java").toString(), aliasChanged.path)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(aliasChanged))
            val changedPsi = requireNotNull(PsiManager.getInstance(project).findFile(aliasChanged))
            val deletedPsi = requireNotNull(PsiManager.getInstance(project).findFile(aliasDeleted))
            assertEquals(originalText, document.text)
            assertEquals(originalText, changedPsi.text)
            assertEquals("class Old {}\n", deletedPsi.text)

            // No refresh or event-loop pumping after these writes: the loaded alias PSI must
            // remain stale until the tool refreshes the selected, registered VFS root.
            Files.writeString(changedPath, changedText)
            Files.delete(deletedPath)
            assertEquals("The alias document must start stale", originalText, document.text)
            assertEquals("The alias PSI must start stale", originalText, changedPsi.text)
            assertTrue("The deleted alias must start cached in VFS", aliasDeleted.isValid)
            assertTrue("The deleted alias PSI must start valid", deletedPsi.isValid)

            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", aliasPath.toString())
            })

            assertToolSucceeded("Full sync should refresh the selected symlink content root", result)
            assertEquals("The loaded alias document must reflect the external edit", changedText, document.text)
            assertEquals("The loaded alias PSI must reflect the external edit", changedText, changedPsi.text)
            assertNull("The deleted alias must be evicted from the VFS", aliasRoot.findChild("Old.java"))
            assertFalse("The deleted alias PSI must be invalidated", deletedPsi.isValid)
            val payload = decode(toolText(result))
            assertTrue(payload.syncedAll)
            assertEquals(listOf(aliasPath.toString()), payload.syncedPaths)
            assertEquals(listOf(aliasPath.toString()), payload.refreshedRoots)
        } finally {
            removeContentRoot(aliasPath)
            Files.deleteIfExists(aliasPath)
            Files.deleteIfExists(changedPath)
            Files.deleteIfExists(deletedPath)
            Files.deleteIfExists(realRoot)
        }
    }

    fun testTargetedSyncMapsCanonicalAbsolutePathToSelectedAliasRoot() = runBlocking {
        val realRoot = Files.createTempDirectory("ide-sync-files-shared-root").toRealPath()
        val aliasPath = Path.of(requireNotNull(project.basePath), "linked-module")
        val changedPath = realRoot.resolve("Changed.java")
        val deletedPath = realRoot.resolve("Old.java")
        val originalText = "class Changed { int before; }\n"
        val changedText = "class Changed { long externallyChanged; }\n"
        Files.writeString(changedPath, originalText)
        Files.writeString(deletedPath, "class Old {}\n")

        try {
            createSymbolicLinkOrSkip(aliasPath, realRoot)
            val localFileSystem = LocalFileSystem.getInstance()
            val canonicalRoot = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(realRoot))
            val aliasRoot = requireNotNull(localFileSystem.refreshAndFindFileByNioFile(aliasPath))
            PsiTestUtil.addContentRoot(module, canonicalRoot)
            PsiTestUtil.addContentRoot(module, aliasRoot)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val aliasChanged = requireNotNull(aliasRoot.findChild("Changed.java"))
            val aliasDeleted = requireNotNull(aliasRoot.findChild("Old.java"))
            assertNotSame(canonicalRoot.findChild("Changed.java"), aliasChanged)
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(aliasChanged))
            val changedPsi = requireNotNull(PsiManager.getInstance(project).findFile(aliasChanged))
            val deletedPsi = requireNotNull(PsiManager.getInstance(project).findFile(aliasDeleted))
            assertEquals(originalText, changedPsi.text)
            assertEquals("class Old {}\n", deletedPsi.text)

            Files.writeString(changedPath, changedText)
            Files.delete(deletedPath)
            assertEquals("The selected alias must start stale", originalText, document.text)
            assertTrue("The deleted alias must still be known to VFS", aliasDeleted.isValid)

            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", aliasPath.toString())
                put("paths", buildJsonArray {
                    add(JsonPrimitive(changedPath.toString()))
                    add(JsonPrimitive("Old.java"))
                })
            })

            assertToolSucceeded("Targeted sync should use the selected alias of the shared root", result)
            assertEquals(changedText, document.text)
            assertEquals(changedText, changedPsi.text)
            assertNull(aliasRoot.findChild("Old.java"))
            assertFalse(deletedPsi.isValid)
            val payload = decode(toolText(result))
            assertFalse(payload.syncedAll)
            assertEquals(listOf("Old.java"), payload.deletedPaths)
            assertEquals(listOf(aliasPath.toString(), aliasPath.resolve("Changed.java").toString()), payload.refreshedRoots)
        } finally {
            removeContentRoot(aliasPath)
            removeContentRoot(realRoot)
            Files.deleteIfExists(aliasPath)
            Files.deleteIfExists(changedPath)
            Files.deleteIfExists(deletedPath)
            Files.deleteIfExists(realRoot)
        }
    }

    fun testTargetedSyncRejectsEscapeFromSymbolicLinkRootBeforeRefreshingValidTarget() = runBlocking {
        val projectRoot = Path.of(requireNotNull(project.basePath))
        writeProjectFile("outside-content/Outside.java", "class Outside {}\n")
        val realRoot = Files.createTempDirectory("ide-sync-files-confined-root").toRealPath()
        val aliasPath = projectRoot.resolve("linked-module")
        val changedPath = realRoot.resolve("Changed.java")
        val escapePath = realRoot.resolve("outside-link")
        val originalText = "class Changed { int before; }\n"
        Files.writeString(changedPath, originalText)

        try {
            createSymbolicLinkOrSkip(aliasPath, realRoot)
            createSymbolicLinkOrSkip(escapePath, projectRoot.resolve("outside-content"))
            val aliasRoot = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(aliasPath))
            PsiTestUtil.addContentRoot(module, aliasRoot)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            val aliasChanged = requireNotNull(aliasRoot.findChild("Changed.java"))
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(aliasChanged))
            assertEquals(originalText, document.text)
            Files.writeString(changedPath, "class Changed { long externallyChanged; }\n")

            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", aliasPath.toString())
                put("paths", buildJsonArray {
                    add(JsonPrimitive("Changed.java"))
                    add(JsonPrimitive("outside-link/Outside.java"))
                })
            })

            assertToolFailed("Canonical containment must reject an escape from the alias root", result)
            assertTrue(toolText(result), toolText(result).contains("symbolic link"))
            assertEquals("An invalid batch must not refresh even its valid first target", originalText, document.text)
        } finally {
            removeContentRoot(aliasPath)
            Files.deleteIfExists(aliasPath)
            Files.deleteIfExists(escapePath)
            Files.deleteIfExists(changedPath)
            Files.deleteIfExists(realRoot)
        }
    }

    fun testSyncResolvesPathsAgainstSelectedWorkspaceContentRoot() = runBlocking {
        // A same-named file under the workspace base makes a base-first implementation lie: the
        // selected sub-project target was deleted and must be resolved against that root.
        writeProjectFile("Shared.java", "public class Shared {}\n")
        val workspaceRootPath = java.nio.file.Path.of(requireNotNull(project.basePath), "workspace-module")
        Files.createDirectories(workspaceRootPath)
        val workspaceRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath(workspaceRootPath.toString())
        )
        PsiTestUtil.addContentRoot(module, workspaceRoot)
        try {
            val deleted = writeProjectFile("workspace-module/Shared.java", "class Shared {}")
            Files.delete(deleted)

            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", workspaceRootPath.toString())
                put("paths", buildJsonArray { add(JsonPrimitive("Shared.java")) })
            })

            assertToolSucceeded("Workspace sync should use the selected content root", result)
            val payload = decode(toolText(result))
            assertEquals(listOf("Shared.java"), payload.deletedPaths)
            assertEquals(listOf(workspaceRootPath.toRealPath().toString()), payload.refreshedRoots)
        } finally {
            removeContentRoot(workspaceRootPath)
        }
    }

    fun testRelativePathWithBaseProjectPathSearchesModuleContentRoots() = runBlocking {
        val contentRootPath = Files.createTempDirectory("ide-sync-files-content-root")
        val targetPath = contentRootPath.resolve("OnlyInContentRoot.java")
        Files.writeString(targetPath, "class OnlyInContentRoot {}\n")
        val contentRoot = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath(contentRootPath.toString())
        )
        PsiTestUtil.addContentRoot(module, contentRoot)

        try {
            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", requireNotNull(project.basePath))
                put("paths", buildJsonArray { add(JsonPrimitive("OnlyInContentRoot.java")) })
            })

            assertToolSucceeded("The project base routing hint should still fall back to module content roots", result)
            val payload = decode(toolText(result))
            assertEquals(listOf("OnlyInContentRoot.java"), payload.syncedPaths)
            assertEquals(listOf(targetPath.toString()), payload.refreshedRoots)
        } finally {
            removeContentRoot(contentRootPath)
            Files.deleteIfExists(targetPath)
            Files.deleteIfExists(contentRootPath)
        }
    }

    fun testAbsolutePathMatchesAllRootsDespiteDifferentSelectedContentRoot() = runBlocking {
        val selectedRootPath = Files.createTempDirectory("ide-sync-files-selected-root")
        val targetRootPath = Files.createTempDirectory("ide-sync-files-target-root")
        val targetPath = targetRootPath.resolve("AbsoluteTarget.java")
        Files.writeString(targetPath, "class AbsoluteTarget {}\n")
        val fileSystem = LocalFileSystem.getInstance()
        val selectedRoot = requireNotNull(fileSystem.refreshAndFindFileByPath(selectedRootPath.toString()))
        val targetRoot = requireNotNull(fileSystem.refreshAndFindFileByPath(targetRootPath.toString()))
        PsiTestUtil.addContentRoot(module, selectedRoot)
        PsiTestUtil.addContentRoot(module, targetRoot)

        try {
            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", selectedRootPath.toString())
                put("paths", buildJsonArray { add(JsonPrimitive(targetPath.toString())) })
            })

            assertToolSucceeded("Absolute containment is unambiguous across allowed roots", result)
            val payload = decode(toolText(result))
            assertEquals(listOf(targetPath.toString()), payload.syncedPaths)
            assertEquals(listOf(targetPath.toString()), payload.refreshedRoots)
        } finally {
            removeContentRoot(selectedRootPath)
            removeContentRoot(targetRootPath)
            Files.deleteIfExists(targetPath)
            Files.deleteIfExists(selectedRootPath)
            Files.deleteIfExists(targetRootPath)
        }
    }

    private fun createSymbolicLinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            Assume.assumeNoException(e)
        }
    }

    private fun removeContentRoot(path: Path) {
        val url = LocalFileSystem.getInstance().findFileByPath(path.toString())?.url
            ?: "file://${path.toString().replace('\\', '/')}"
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.filter { it.url == url }.forEach(model::removeContentEntry)
        }
    }
}
