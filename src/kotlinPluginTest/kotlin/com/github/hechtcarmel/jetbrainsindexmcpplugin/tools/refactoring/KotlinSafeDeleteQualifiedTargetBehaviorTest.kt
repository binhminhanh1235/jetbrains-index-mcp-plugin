package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class KotlinSafeDeleteQualifiedTargetBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("semantic-src")
        writeProjectFile("semantic-src/Service.kt", """
            package lighttarget
            class Service {
                fun compute(): Int { return 1 }
                fun keep(): Int { return 7 }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
            SymbolIdRegistry.getInstance().resetSession()
        } finally {
            super.tearDown()
        }
    }

    private fun source(): String = ReadAction.compute<String, Throwable> {
        readProjectFileVfs("semantic-src/Service.kt")
    }

    fun testSafeDeleteLegacyAndNestedQualifiedJavaSelectorsDeleteKotlinSource() = runBlocking {
        val legacyArgs = buildJsonObject {
            put("language", "Java")
            put("symbol", "lighttarget.Service#compute()")
        }
        val before = source()
        val preview = SafeDeleteTool().execute(
            project,
            JsonObject(legacyArgs + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Preview Kotlin source through a legacy Java signature", preview)
        val plan = Json { ignoreUnknownKeys = true }.decodeFromString<RefactoringPreviewResult>(toolText(preview))
        assertTrue(plan.toString(), plan.canApply)
        assertEquals(before, source())
        val applied = SafeDeleteTool().execute(project, buildJsonObject {
            putJsonObject("target") {
                put("language", "Java")
                put("qualifiedName", "lighttarget.Service#compute()")
            }
        })
        assertToolSucceeded(
            "Nested qualified target must delete the declaration that legacy preview resolved",
            applied
        )
        assertFalse(source().contains("fun compute"))
        assertTrue(source().contains("fun keep(): Int { return 7 }"))
    }

    fun testPreviewPreservesExactKotlinLightMethodHandleIdentity() = runBlocking {
        val registry = SymbolIdRegistry.getInstance()
        val (symbolId, lightMethodClass) = ReadAction.compute<Pair<String, String>, Throwable> {
            val resolved = requireNotNull(
                LanguageHandlerRegistry.getSymbolReferenceHandlerByLanguageName("Java")
            ).resolveSymbol(project, "lighttarget.Service#compute()").getOrThrow()
            assertTrue("Java-signature lookup must expose a light method", resolved is PsiMethod)
            assertNotSame(
                "The probe requires distinct light and source PSI identities",
                resolved,
                PsiUtils.resolveNavigationTarget(resolved)
            )
            registry.bind(project, resolved) to resolved.javaClass.name
        }

        val previewResult = SafeDeleteTool().execute(project, buildJsonObject {
            putJsonObject("target") { put("symbolId", symbolId) }
            put("dryRun", true)
        })
        assertToolSucceeded("Preview an exact Kotlin light-method handle", previewResult)
        assertTrue(
            Json { ignoreUnknownKeys = true }
                .decodeFromString<RefactoringPreviewResult>(toolText(previewResult))
                .canApply
        )

        val resolvedAfterPreview = ReadAction.compute<Any, Throwable> {
            registry.resolve(project, symbolId).getOrThrow()
        }
        assertTrue(
            "Preview must not rebind a light-method handle to its source declaration",
            resolvedAfterPreview is PsiMethod
        )
        assertEquals(lightMethodClass, resolvedAfterPreview.javaClass.name)

        val applyResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("symbolId", symbolId)
        })
        assertToolSucceeded("Apply through the preserved light-method handle", applyResult)
        val applied = Json { ignoreUnknownKeys = true }
            .decodeFromString<RefactoringResult>(toolText(applyResult))
        assertEquals(symbolId, applied.invalidatedSymbolId)
        assertFalse(source().contains("fun compute"))
        assertTrue(ReadAction.compute<Boolean, Throwable> {
            registry.resolve(project, symbolId).isFailure
        })
    }

    fun testKotlinBaseFunctionWithOverrideIsBlockedWithoutOrdinaryReferences() = runBlocking {
        val file = "semantic-src/Hierarchy.kt"
        val before = """
            package lighttarget

            open class HierarchyBase {
                open fun run() {}
            }

            class HierarchyChild : HierarchyBase() {
                override fun run() {}
            }
        """.trimIndent()
        writeProjectFile(file, before)
        val arguments = buildJsonObject {
            put("language", "Java")
            put("symbol", "lighttarget.HierarchyBase#run()")
        }

        val previewResult = SafeDeleteTool().execute(
            project,
            JsonObject(arguments + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Kotlin hierarchy preview must return a structured blocker", previewResult)
        val preview = Json { ignoreUnknownKeys = true }
            .decodeFromString<RefactoringPreviewResult>(toolText(previewResult))
        assertFalse("A Kotlin override must block deleting its base function", preview.canApply)
        assertTrue("The override must be counted as a usage", preview.usageCount > 0)

        val applyResult = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("Blocked Kotlin hierarchy apply must be structured", applyResult)
        val blocked = Json { ignoreUnknownKeys = true }
            .decodeFromString<SafeDeleteBlockedResult>(toolText(applyResult))
        assertFalse(blocked.canDelete)
        assertTrue(blocked.blockingUsages.any { it.file == file })
        assertEquals(before, ReadAction.compute<String, Throwable> { readProjectFileVfs(file) })
    }
}
