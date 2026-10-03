package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ChangeSignatureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RefactoringPreviewResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assume

class UnifiedTargetToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    fun testAbsentLegacyCoordinatePlaceholdersDoNotBreakANestedTarget() = runBlocking {
        registerSourceRoot("placeholder-target-src")
        writeProjectFile("placeholder-target-src/unifiedtarget/Service.java", javaSource())
        for (placeholder in listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(" "))) {
            val definition = FindDefinitionTool().execute(project, buildJsonObject {
                qualifiedMethodTarget()
                put("line", placeholder)
                put("column", placeholder)
            })
            assertToolSucceeded("Absent legacy coordinates must not mask the nested target", definition)
            val symbol = json.decodeFromString<DefinitionResult>(toolText(definition))
            assertEquals("work", symbol.symbolName)
            val lookup = SymbolInfoTool().execute(project, buildJsonObject {
                putJsonObject("target") { put("symbolId", symbol.symbolId) }
                put("line", placeholder)
                put("column", placeholder)
            })
            assertToolSucceeded("Nested symbolId must also ignore absent legacy coordinates", lookup)
        }
    }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        SymbolIdRegistry.getInstance().resetSession()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testNestedPositionFlowsThroughNormalizationIntoSemanticTool() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        val source = javaSource()
        writeProjectFile("nested-position-src/unifiedtarget/Service.java", source)
        val offset = source.indexOf("work")
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1

        val result = FindDefinitionTool().execute(project, buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "nested-position-src/unifiedtarget/Service.java")
                    put("line", source.substring(0, offset).count { it == '\n' } + 1)
                    put("column", offset - lineStart + 1)
                }
            }
        })

        assertToolSucceeded("nested target.position should resolve", result)
        assertEquals("work", json.decodeFromString<DefinitionResult>(toolText(result)).symbolName)
    }

    fun testNestedQualifiedNameWorksForSemanticLookupAndAllPreviewRefactorings() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("qualified-target-src")
        val path = writeProjectFile("qualified-target-src/unifiedtarget/Service.java", javaSource())
        val before = Files.readAllBytes(path)

        val info = SymbolInfoTool().execute(project, buildJsonObject {
            qualifiedMethodTarget()
        })
        assertToolSucceeded("nested target.qualifiedName should resolve in semantic tools", info)
        assertTrue(toolText(info).contains("work"))

        val rename = RenameSymbolTool().execute(project, buildJsonObject {
            qualifiedMethodTarget()
            put("newName", "compute")
            put("dryRun", true)
        })
        assertApplicablePreview("rename", rename)

        val safeDelete = SafeDeleteTool().execute(project, buildJsonObject {
            qualifiedMethodTarget()
            put("force", true)
            put("dryRun", true)
        })
        assertApplicablePreview("safe delete", safeDelete)

        val changeSignature = ChangeSignatureTool().execute(project, buildJsonObject {
            qualifiedMethodTarget()
            put("newName", "compute")
            put("dryRun", true)
        })
        assertApplicablePreview("change signature", changeSignature)

        assertTrue("nested-target previews changed the source file", before.contentEquals(Files.readAllBytes(path)))
    }

    fun testChangeSignaturePositionIgnoresAnIncompleteLegacySymbolPlaceholder() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("placeholder-signature-src")
        val source = javaSource()
        val path = writeProjectFile("placeholder-signature-src/unifiedtarget/Service.java", source)
        val before = Files.readAllBytes(path)
        val offset = source.indexOf("work")
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val position = mapOf(
            "file" to JsonPrimitive("placeholder-signature-src/unifiedtarget/Service.java"),
            "line" to JsonPrimitive(source.substring(0, offset).count { it == '\n' } + 1),
            "column" to JsonPrimitive(offset - lineStart + 1),
            "newName" to JsonPrimitive("compute"),
            "dryRun" to JsonPrimitive(true)
        )

        for ((name, value) in listOf("language" to "Java", "symbol" to "unifiedtarget.Service#work(int)")) {
            val result = ChangeSignatureTool().execute(
                project,
                kotlinx.serialization.json.JsonObject(position + (name to JsonPrimitive(value)))
            )
            assertApplicablePreview("change signature with only $name placeholder", result)
        }

        assertTrue("change-signature previews changed the source file", before.contentEquals(Files.readAllBytes(path)))
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.qualifiedMethodTarget() {
        putJsonObject("target") {
            put("qualifiedName", "unifiedtarget.Service#work(int)")
            put("language", "Java")
        }
    }

    private fun assertApplicablePreview(label: String, result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult) {
        assertToolSucceeded("$label preview should resolve nested target.qualifiedName", result)
        val preview = json.decodeFromString<RefactoringPreviewResult>(toolText(result))
        assertTrue("$label preview should be applicable: ${preview.warnings}", preview.canApply)
        assertEquals("work", preview.target.name)
    }

    private fun javaSource() = """
        package unifiedtarget;

        public class Service {
            public int work(int value) { return value; }
        }
    """.trimIndent()
}
