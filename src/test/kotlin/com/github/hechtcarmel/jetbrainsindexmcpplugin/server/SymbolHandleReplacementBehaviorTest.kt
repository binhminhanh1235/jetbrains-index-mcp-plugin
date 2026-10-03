package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Regression probes: no old-handle lookup is made between deletion and recreation. */
class SymbolHandleReplacementBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val relativeFile = "replacement-src/Target.java"
    private val originalSource = "class Target { void work() {} }"
    private val replacementSource = "class Target { void evil() {} }"

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        SymbolIdRegistry.getInstance().resetSession()
        registerSourceRoot("replacement-src")
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testMethodHandleExpiresWhenItsFileIsDeletedAndRecreated() = runBlocking {
        writeProjectFile(relativeFile, originalSource)
        val discovered = FindDefinitionTool().execute(project, buildJsonObject {
            put("file", relativeFile)
            put("line", 1)
            put("column", originalSource.indexOf("work") + 1)
        })
        assertToolSucceeded("Discover original method", discovered)
        val definition = json.decodeFromString<DefinitionResult>(toolText(discovered))
        assertEquals("work", definition.symbolName)

        replaceFileThroughVfs()

        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
        })
        assertToolFailed("A deleted method must not resolve to a declaration in a replacement file", result)
        assertTrue(toolText(result), toolText(result).contains("SYMBOL_ID_EXPIRED"))
    }

    fun testFileHandleExpiresWhenTheFileIsDeletedAndRecreated() {
        writeProjectFile(relativeFile, originalSource)
        val handle = ReadAction.compute<String, Throwable> {
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile(relativeFile)))
            SymbolIdRegistry.getInstance().bind(project, psiFile)
        }

        replaceFileThroughVfs()

        assertExpired(handle)
    }

    fun testDirectoryHandleExpiresWhenTheDirectoryIsDeletedAndRecreated() {
        val relativeDirectory = "replacement-src/packageDir"
        writeProjectFile("$relativeDirectory/Old.java", "class Old {}")
        val oldDirectory = virtualFile(relativeDirectory)
        val handle = ReadAction.compute<String, Throwable> {
            val psiDirectory = requireNotNull(PsiManager.getInstance(project).findDirectory(oldDirectory))
            SymbolIdRegistry.getInstance().bind(project, psiDirectory)
        }

        WriteCommandAction.runWriteCommandAction(project) { oldDirectory.delete(this) }
        assertFalse("The original VFS directory must be invalid", oldDirectory.isValid)
        writeProjectFile("$relativeDirectory/New.java", "class New {}")
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNotSame(oldDirectory, virtualFile(relativeDirectory))

        assertExpired(handle)
    }

    private fun replaceFileThroughVfs() {
        val oldFile = virtualFile(relativeFile)
        WriteCommandAction.runWriteCommandAction(project) { oldFile.delete(this) }
        assertFalse("The original VFS file must be invalid", oldFile.isValid)
        writeProjectFile(relativeFile, replacementSource)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val replacement = virtualFile(relativeFile)
        assertNotSame(oldFile, replacement)
        assertEquals(replacementSource, readProjectFileVfs(relativeFile))
    }

    private fun virtualFile(relativePath: String): VirtualFile = requireNotNull(
        LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$relativePath")
    )

    private fun assertExpired(handle: String) {
        ReadAction.run<Throwable> {
            val result = SymbolIdRegistry.getInstance().resolve(project, handle)
            assertTrue(
                "Deleted target was restored from a replacement: ${result.getOrNull()}",
                result.isFailure
            )
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SYMBOL_ID_EXPIRED"))
        }
    }
}
