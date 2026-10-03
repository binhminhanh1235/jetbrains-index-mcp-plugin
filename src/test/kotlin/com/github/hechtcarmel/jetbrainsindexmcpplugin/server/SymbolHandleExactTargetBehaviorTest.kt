package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SyncFilesTool
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

class SymbolHandleExactTargetBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
        registerSourceRoot("exact-handle-src")
    }

    override fun tearDown() {
        try { SymbolIdRegistry.getInstance().resetSession() } finally { super.tearDown() }
    }

    fun testReadToolsPreserveANonNamedTargetInsteadOfResolvingItsParentOrReference() = runBlocking {
        val path = writeProjectFile(
            "exact-handle-src/Calls.java", "class Calls { void work() {} void caller() { work(); } }"
        )
        val target = ReadAction.compute<PsiMethodCallExpression, Throwable> {
            val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
            val file = requireNotNull(PsiManager.getInstance(project).findFile(vf))
            requireNotNull(PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression::class.java))
        }
        val handle = ReadAction.compute<String, Throwable> { SymbolIdRegistry.getInstance().bind(project, target) }
        val args = buildJsonObject {
            put("symbolId", handle)
            put("fullElementPreview", true)
            put("includeDoc", false)
        }
        val definition = FindDefinitionTool().execute(project, args)
        assertToolSucceeded("Exact lookup of a non-named PSI target", definition)
        assertEquals("work()", json.decodeFromString<DefinitionResult>(toolText(definition)).preview)
        val info = SymbolInfoTool().execute(project, args)
        assertToolSucceeded("Exact symbol metadata lookup", info)
        ReadAction.run<Throwable> {
            assertSame(target, SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow())
        }
    }

    fun testImplicitEnumMethodsWithoutTextHaveUsableDefinitionPreviews() = runBlocking {
        val path = writeProjectFile("exact-handle-src/Choice.java", "enum Choice { FIRST, SECOND }")
        val handles = ReadAction.compute<List<String>, Throwable> {
            val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
            val file = PsiManager.getInstance(project).findFile(vf) as PsiJavaFile
            val enumClass = file.classes.single()
            enumClass.methods.filter { it.name in setOf("values", "valueOf") }.map {
                assertNull("Implicit enum methods have no source text", it.text)
                SymbolIdRegistry.getInstance().bind(project, it)
            }
        }
        assertEquals("The fixture must expose both implicit enum methods", 2, handles.size)
        for (handle in handles) {
            val definition = FindDefinitionTool().execute(project, buildJsonObject {
                put("symbolId", handle)
                put("fullElementPreview", true)
            })
            assertToolSucceeded("Implicit enum method definition", definition)
            val result = json.decodeFromString<DefinitionResult>(toolText(definition))
            assertEquals(handle, result.symbolId)
            assertTrue("Source context replaces unavailable synthetic text", result.preview.contains("enum Choice"))
        }
    }

    fun testImplicitEnumMethodHandleExpiresAfterItsDeclaringEnumIsRemoved() = runBlocking {
        val relativePath = "exact-handle-src/RemovedChoice.java"
        val path = writeProjectFile(relativePath, "enum RemovedChoice { FIRST, SECOND }")
        val handle = ReadAction.compute<String, Throwable> {
            val vf = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
            val file = PsiManager.getInstance(project).findFile(vf) as PsiJavaFile
            val valuesMethod = requireNotNull(file.classes.single().findMethodsByName("values", false).singleOrNull())
            assertNull("Implicit enum methods have no source text", valuesMethod.text)
            SymbolIdRegistry.getInstance().bind(project, valuesMethod)
        }

        Files.writeString(path, "class Replacement {}")
        val sync = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(relativePath) })
        })
        assertToolSucceeded("Synchronize removal of the declaring enum", sync)

        ReadAction.run<Throwable> {
            val resolved = SymbolIdRegistry.getInstance().resolve(project, handle)
            assertTrue("A synthetic method must not outlive its declaring enum", resolved.isFailure)
            assertTrue(resolved.exceptionOrNull()?.message.orEmpty().contains("SYMBOL_ID_EXPIRED"))
        }
    }
}
