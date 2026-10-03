package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class ChangeSignatureExternalFileBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testCoordinateApplyRefreshesAnExternalTargetMissingFromVfs() = runBlocking {
        assertFalse(ApplicationManager.getApplication().isDispatchThread)
        assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
        val settings = McpSettings.getInstance()
        val priorSync = settings.syncExternalChanges
        settings.syncExternalChanges = false
        try {
            registerSourceRoot("external-signature-src")
            // Index the package before creating the target; this isolates file discovery from
            // the IDE's asynchronous initial source-root and file-type setup.
            val anchor = writeProjectFile(
                "external-signature-src/external/Anchor.java", "package external; class Anchor {}"
            )
            val packageDirectory = requireNotNull(LocalFileSystem.getInstance().findFileByPath(anchor.parent.toString()))
            // Complete the VFS child listing before an out-of-band write. A cached missing
            // child then needs refreshAndFindFileByPath, which resolveFile suppresses in RA.
            ReadAction.run<Throwable> { packageDirectory.children }
            val file = "external-signature-src/external/ExternalTarget.java"
            val path = Path.of(requireNotNull(project.basePath), file)
            val source = "package external; class ExternalTarget { void work() {} }"
            Files.writeString(path, source)
            assertNull(
                "The external target must be absent from the VFS cache",
                LocalFileSystem.getInstance().findFileByPath(path.toString())
            )

            val result = ChangeSignatureTool().execute(project, buildJsonObject {
                put("file", file)
                put("line", 1)
                put("column", source.indexOf("work") + 1)
                put("newName", "compute")
            })
            assertToolSucceeded("Coordinate apply must refresh the existing external target", result)
            val after = Files.readString(path)
            assertTrue("Applied method should be saved: $after", after.contains("compute()"))
            assertFalse("Original method should be gone: $after", after.contains("work()"))
        } finally {
            settings.syncExternalChanges = priorSync
        }
    }
}
