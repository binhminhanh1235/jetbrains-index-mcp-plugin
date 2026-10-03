package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import java.nio.file.Files

class SyncFilesRoutingBehaviorTest : McpPlatformTestCase() {

    fun testExplicitNullProjectPathUsesResolvedProject() = runBlocking {
        val original = "class NullRouting { int before; }\n"
        val changed = "class NullRouting { long externallyChanged; }\n"
        val diskFile = writeProjectFile("routing-null/NullRouting.java", original)
        val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(diskFile.toString()))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(vf))
        val psi = requireNotNull(PsiManager.getInstance(project).findFile(vf))
        assertEquals(original, document.text)
        assertEquals(original, psi.text)
        Files.writeString(diskFile, changed)
        assertEquals("Precondition: loaded document is stale", original, document.text)

        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("project_path", JsonNull)
            put("paths", buildJsonArray { add("routing-null/NullRouting.java") })
        })

        assertToolSucceeded("The dispatcher accepts JSON null as an omitted project path", result)
        assertEquals(changed, document.text)
        assertEquals(changed, psi.text)
    }

    fun testDeletedRoutingDirectoryStillSynchronizesKnownDeletedFile() = runBlocking {
        val diskFile = writeProjectFile("routing-deleted/subdir/Old.java", "class Old {}\n")
        val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(diskFile.toString()))
        val psi = requireNotNull(PsiManager.getInstance(project).findFile(vf))
        val routingDirectory = diskFile.parent
        Files.delete(diskFile)
        Files.delete(routingDirectory)
        assertTrue("Precondition: deleted file is still known to VFS", vf.isValid)
        assertTrue("Precondition: deleted file PSI is still valid", psi.isValid)

        // ProjectResolver routes this lexically by the existing project's base-path prefix;
        // it deliberately does not require the subdirectory to still exist on disk.
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("project_path", routingDirectory.toString())
        })

        assertToolSucceeded("A routing subdirectory need not remain on disk to refresh its project", result)
        assertFalse("The full sync must invalidate the deleted file PSI", psi.isValid)
        assertNull(LocalFileSystem.getInstance().findFileByPath(diskFile.toString()))
    }

    fun testFullSyncInvalidatesDeletedRegisteredContentRoot() = runBlocking {
        val diskFile = writeProjectFile("routing-content-root/Old.java", "class Old {}\n")
        val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(diskFile.toString()))
        val root = requireNotNull(vf.parent)
        PsiTestUtil.addContentRoot(module, root)
        try {
            val psi = requireNotNull(PsiManager.getInstance(project).findFile(vf))
            Files.delete(diskFile)
            Files.delete(diskFile.parent)
            assertTrue("Precondition: deleted content root is still known to VFS", root.isValid)
            assertTrue("Precondition: deleted file PSI is still valid", psi.isValid)

            val result = SyncFilesTool().execute(project, buildJsonObject {
                put("project_path", root.path)
            })

            assertToolSucceeded("Full sync must invalidate a registered content root deleted externally", result)
            assertFalse("Deleted content root should be invalidated", root.isValid)
            assertFalse("Deleted content root's PSI should be invalidated", psi.isValid)
        } finally {
            ModuleRootModificationUtil.updateModel(module) { model ->
                model.contentEntries.filter { it.url == root.url }.forEach(model::removeContentEntry)
            }
        }
    }
}
