package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Exercise filesystem discovery from the tool's normal background execution thread. */
class SyncFilesNewTargetScopeBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testTargetingANewTopLevelFileDoesNotRecursivelyRefreshUnrelatedDirectories() = runBlocking {
        val visible = writeProjectFile("unrelated-deep/Visible.java", "class Visible {}\n")
        val fileSystem = LocalFileSystem.getInstance()
        val basePath = Path.of(requireNotNull(project.basePath))
        val baseVf = requireNotNull(fileSystem.findFileByPath(basePath.toString()))
        val unrelatedVf = requireNotNull(fileSystem.findFileByPath(visible.parent.toString()))
        baseVf.children
        unrelatedVf.children
        val requested = basePath.resolve("FreshTarget.java")
        val unrelated = visible.parent.resolve("UnrelatedTarget.java")
        Files.writeString(requested, "class FreshTarget {}\n")
        Files.writeString(unrelated, "class UnrelatedTarget {}\n")
        assertNull("Requested file starts invisible", fileSystem.findFileByPath(requested.toString()))
        assertNull("Unrelated file starts invisible", fileSystem.findFileByPath(unrelated.toString()))

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add("FreshTarget.java") })
        })

        assertToolSucceeded("Targeted sync should discover the newly written top-level file", result)
        assertNotNull("The requested file must become visible", fileSystem.findFileByPath(requested.toString()))
        assertNull(
            "A single new file should not recursively refresh every unrelated directory in the project",
            fileSystem.findFileByPath(unrelated.toString())
        )
    }
}
