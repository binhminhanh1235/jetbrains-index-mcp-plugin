package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** Real Kotlin PSI, including the interactive boundary that Kotlin's test mode auto-confirms. */
class KotlinRenameBaseBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load the Kotlin plugin", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("rename-src")
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testRenameBaseUpdatesInterfaceOverridesAndCallsWithoutChoosingTarget() = runBlocking {
        val files = createHierarchy()
        val result = headlessTool().execute(project, renameRequest())
        assertToolSucceeded("rename_base must resolve the Kotlin base without an IDE chooser", result)
        assertHierarchyRenamed(files)
    }

    fun testDefaultStrategyUpdatesTheHierarchyWithoutChoosingTarget() = runBlocking {
        val files = createHierarchy()
        val result = headlessTool().execute(project, renameRequest(strategy = null, relatedStrategy = null))
        assertToolSucceeded("The default strategy must also rename the Kotlin base without a chooser", result)
        assertHierarchyRenamed(files)
    }

    fun testRenameBasePreviewResolvesTheInterfaceAndPreservesFiles() = runBlocking {
        val files = createHierarchy()
        val result = headlessTool().execute(project, renameRequest(dryRun = true))
        assertToolSucceeded("The Kotlin base must be discoverable by a headless preview", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue("Preview must be complete: ${toolText(result)}", preview.getValue("canApply").jsonPrimitive.boolean)
        assertEquals("rename-src/Contract.kt", preview.getValue("target").jsonObject.getValue("file").jsonPrimitive.content)
        assertSavedFiles(files)
    }

    fun testFailedBaseDiscoveryDoesNotChooseInteractivelyOrModifyFiles() = runBlocking {
        val files = createHierarchy()
        val tool = headlessTool().apply {
            deepestSuperMethodResolutionHook = { error("Base discovery deliberately unavailable") }
        }
        val result = tool.execute(project, renameRequest())
        assertToolFailed("An unresolved base must abort the headless rename", result)
        assertTrue(toolText(result), toolText(result).contains("Base discovery deliberately unavailable"))
        assertSavedFiles(files)
    }

    fun testRenameBaseUpdatesJavaInterfaceAndKotlinOverridesWithoutChoosingTarget() = runBlocking {
        val files = createHierarchy(javaContract = true)
        val result = headlessTool().execute(project, renameRequest())
        assertToolSucceeded("A Java base of a Kotlin override must be renamed without a chooser", result)
        assertHierarchyRenamed(files)
    }

    fun testRenameBaseWithoutSuperMethodDoesNotChooseInteractively() = runBlocking {
        val file = "rename-src/Implementation.kt"
        val original = """
            package renameprobe
            class Implementation {
                fun perform() {}
            }
            fun call(implementation: Implementation) { implementation.perform() }
        """.trimIndent()
        writeProjectFile(file, original)
        val result = headlessTool().execute(project, renameRequest(column = 9))
        assertToolSucceeded("A Kotlin method without a base must also avoid target selection", result)
        assertHierarchyRenamed(mapOf(file to original))
    }

    fun testDefaultPreviewWithoutSuperMethodIsApplicableAndPreservesFiles() = runBlocking {
        val file = "rename-src/Implementation.kt"
        val original = """
            package renameprobe
            class Implementation {
                fun perform(): Int = 1
                fun use(): Int = perform()
            }
        """.trimIndent()
        writeProjectFile(file, original)
        val result = headlessTool().execute(
            project,
            renameRequest(strategy = null, relatedStrategy = null, dryRun = true, column = 9)
        )
        assertToolSucceeded("A regular Kotlin function must have an applicable default preview", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(toolText(result), preview.getValue("canApply").jsonPrimitive.boolean)
        assertEquals(file, preview.getValue("target").jsonObject.getValue("file").jsonPrimitive.content)
        assertSavedFiles(mapOf(file to original))
    }

    fun testRenameBaseBySourceHandlePreservesSameFileContract() = runBlocking {
        val file = "rename-src/Implementation.kt"
        val original = """
            package renameprobe

            interface Contract {
                fun transform(value: Int): Int
            }

            class Implementation : Contract {
                override fun transform(value: Int): Int = value + 1

                fun use(): Int = transform(1)
            }
        """.trimIndent()
        writeProjectFile(file, original)
        val lookup = SymbolInfoTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", 8)
            put("column", 18)
            put("includeDoc", false)
        })
        assertToolSucceeded("Symbol info must return a handle for the Kotlin override", lookup)
        val symbolId = Json.parseToJsonElement(toolText(lookup)).jsonObject
            .getValue("symbolId").jsonPrimitive.content
        val result = headlessTool().execute(project, buildJsonObject {
            put("symbolId", symbolId)
            put("newName", "transformRenamed")
            put("overrideStrategy", "rename_base")
            put("relatedRenamingStrategy", "none")
        })
        assertToolSucceeded("A hierarchy-derived handle must rename the base without a chooser", result)
        assertSavedFiles(mapOf(file to original.replace("transform", "transformRenamed")))

        val after = SymbolInfoTool().execute(project, buildJsonObject {
            put("symbolId", symbolId)
            put("includeDoc", false)
        })
        assertToolSucceeded("The original handle must still resolve to the renamed override", after)
        val updated = Json.parseToJsonElement(toolText(after)).jsonObject
        assertEquals("transformRenamed", updated.getValue("name").jsonPrimitive.content)
        assertEquals("8", updated.getValue("line").jsonPrimitive.content)
    }

    fun testAskStillEntersInteractiveTargetSelection() = runBlocking {
        val files = createHierarchy()
        val tool = RenameSymbolTool().apply {
            interactiveTargetSelectionHook = { error("Explicit interactive choice reached") }
        }
        val result = tool.execute(project, renameRequest(strategy = "ask"))
        assertToolFailed("The observation hook must stop the explicitly interactive path", result)
        assertTrue(toolText(result), toolText(result).contains("Explicit interactive choice reached"))
        assertSavedFiles(files)
    }

    private fun headlessTool() = RenameSymbolTool().apply {
        // The bundled Kotlin processor returns the base automatically in unit-test mode,
        // so checking renamed text alone cannot prove the real IDE would avoid its chooser.
        interactiveTargetSelectionHook = { error("Interactive target selection must not be entered") }
    }

    private fun createHierarchy(javaContract: Boolean = false): Map<String, String> {
        val contract = if (javaContract) "rename-src/Contract.java" to """
            package renameprobe;
            public interface Contract {
                void perform();
            }
        """.trimIndent() else "rename-src/Contract.kt" to """
            package renameprobe
            interface Contract {
                fun perform()
            }
        """.trimIndent()
        val files = linkedMapOf(contract, "rename-src/Implementation.kt" to """
            package renameprobe
            class Implementation : Contract {
                override fun perform() {}
            }
        """.trimIndent(), "rename-src/Sibling.kt" to """
            package renameprobe
            class Sibling : Contract {
                override fun perform() {}
            }
        """.trimIndent(), "rename-src/Calls.kt" to """
            package renameprobe
            fun call(contract: Contract, implementation: Implementation, sibling: Sibling) {
                contract.perform()
                implementation.perform()
                sibling.perform()
            }
        """.trimIndent())
        files.forEach { (file, source) -> writeProjectFile(file, source) }
        return files
    }

    private fun renameRequest(
        strategy: String? = "rename_base",
        relatedStrategy: String? = "none",
        dryRun: Boolean = false,
        column: Int = 18
    ): JsonObject = buildJsonObject {
        put("file", "rename-src/Implementation.kt")
        put("line", 3)
        put("column", column)
        put("newName", "execute")
        strategy?.let { put("overrideStrategy", it) }
        relatedStrategy?.let { put("relatedRenamingStrategy", it) }
        put("dryRun", dryRun)
    }

    private fun assertHierarchyRenamed(files: Map<String, String>) =
        assertSavedFiles(files.mapValues { (_, original) -> original.replace("perform", "execute") })

    private fun assertSavedFiles(expected: Map<String, String>) {
        for ((file, source) in expected) assertEquals(file, source, Files.readString(projectPath(file)))
    }

    private fun projectPath(file: String): Path = Path.of(requireNotNull(project.basePath), file)
}
