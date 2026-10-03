package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files

class KotlinSafeDeleteParameterBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("parameter-src")
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testUsedLambdaParameterRefusesDeletionWithoutProcessorAssertion() = assertUsedParameter(
        "fun use() { val callback = { item: Int -> item + 1 } }", "item"
    )
    fun testUsedCatchParameterRefusesDeletionWithoutProcessorAssertion() = assertUsedParameter(
        "fun use() { try {} catch (failure: Exception) { println(failure) } }", "failure"
    )
    fun testUsedLoopParameterRefusesDeletionWithoutProcessorAssertion() = assertUsedParameter(
        "fun use(values: List<Int>) { for (item in values) { println(item) } }", "item"
    )

    private fun assertUsedParameter(source: String, name: String) = runBlocking {
        val path = writeProjectFile("parameter-src/Binding.kt", source)
        val before = Files.readAllBytes(path)
        val arguments = buildJsonObject {
            put("file", "parameter-src/Binding.kt")
            put("line", 1)
            put("column", source.indexOf(name) + 1)
        }
        val preview = SafeDeleteTool().execute(project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true))))
        assertToolSucceeded("Preview a used Kotlin binding", preview)
        val plan = Json.parseToJsonElement(toolText(preview)).jsonObject
        assertFalse(plan.toString(), plan.getValue("canApply").jsonPrimitive.boolean)
        assertTrue("A real usage must block deletion", plan.getValue("usageCount").jsonPrimitive.int > 0)
        val applied = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("Return a structured usage refusal", applied)
        val blocked = Json { ignoreUnknownKeys = true }.decodeFromString<SafeDeleteBlockedResult>(toolText(applied))
        assertFalse(blocked.canDelete)
        assertTrue(blocked.blockingUsages.any { it.file == "parameter-src/Binding.kt" })
        assertTrue("Refused deletion preserves disk bytes", before.contentEquals(Files.readAllBytes(path)))
    }
}
