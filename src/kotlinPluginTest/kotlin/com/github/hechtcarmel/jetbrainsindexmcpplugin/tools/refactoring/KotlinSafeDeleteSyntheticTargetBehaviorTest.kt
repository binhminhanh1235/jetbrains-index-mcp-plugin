package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinSafeDeleteSyntheticTargetBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("synthetic-delete-src")
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testImplicitConstructorSelectorCannotDeleteEnclosingClass() = runBlocking {
        val path = "synthetic-delete-src/Service.kt"
        val before = "package syntheticdelete\nclass Service { fun keep(): Int = 7 }"
        writeProjectFile(path, before)
        assertGeneratedTargetRejected(path, before, "syntheticdelete.Service#Service()")
    }

    fun testGeneratedCopySelectorCannotDeleteEnclosingClass() = runBlocking {
        val path = "synthetic-delete-src/Value.kt"
        val before = "package syntheticdelete\ndata class Value(val value: Int) { fun keep(): Int = 7 }"
        writeProjectFile(path, before)
        assertGeneratedTargetRejected(path, before, "syntheticdelete.Value#copy(int)")
    }

    fun testGeneratedAccessorCannotDeleteTheProperty() = runBlocking {
        val path = "synthetic-delete-src/Bean.kt"
        val before = "package syntheticdelete\nclass Bean { var value: Int = 0 }"
        writeProjectFile(path, before)
        assertGeneratedTargetRejected(path, before, "syntheticdelete.Bean#getValue()")
    }

    private suspend fun assertGeneratedTargetRejected(path: String, before: String, symbol: String) {
        for (dryRun in listOf(true, false)) {
            for (force in listOf(false, true)) {
                val result = SafeDeleteTool().execute(project, buildJsonObject {
                    put("language", "Java")
                    put("symbol", symbol)
                    put("dryRun", dryRun)
                    put("force", force)
                })
                assertToolFailed("Generated targets must be rejected for dryRun=$dryRun, force=$force", result)
                assertTrue(toolText(result), toolText(result).contains("no standalone Kotlin declaration"))
                val after = ReadAction.compute<String, Throwable> { readProjectFileVfs(path) }
                assertEquals("A generated member cannot delete a broader source declaration", before, after)
            }
        }
    }
}
