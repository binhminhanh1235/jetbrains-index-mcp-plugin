package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class KotlinChangeSignatureBehaviorTest : McpPlatformTestCase() {
    private val file = "signature-src/Implementation.kt"
    private val source = """
        package signatureprobe

        interface Contract {
            fun transform(value: Int): Int
        }

        class Implementation : Contract {
            override fun transform(value: Int): Int = value + 1

            fun use(): Int = transform(1)
        }
        fun call(implementation: Implementation): Int = implementation.use()
    """.trimIndent()

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load the Kotlin plugin", PluginDetectors.kotlin.isAvailable)
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("signature-src")
        writeProjectFile(file, source)
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testKotlinRenamePreviewByPositionIsApplicableAndPreservesSource() = runBlocking {
        val result = ChangeSignatureTool().execute(project, renameRequest(dryRun = true))
        assertToolSucceeded("A Kotlin declaration must be accepted for preview", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(toolText(result), preview.getValue("canApply").jsonPrimitive.boolean)
        assertSource(source)
    }

    fun testKotlinRenameByPositionUpdatesDeclarationAndCall() = runBlocking {
        val result = ChangeSignatureTool().execute(project, renameRequest())
        assertSource(source.replace("use()", "useRenamed()"))
        assertToolSucceeded("A Kotlin declaration must be accepted for apply", result)
    }

    fun testFreshKotlinOutlineHandleCanChangeSignatureAndRemainUsable() = runBlocking {
        val handle = symbolHandle("use")
        assertHandleUsable(handle)
        val result = ChangeSignatureTool().execute(project, renameRequest(symbolId = handle))
        assertSource(source.replace("use()", "useRenamed()"))
        assertToolSucceeded("The live Kotlin handle must identify a change-signature target", result)
        assertHandleUsable(handle)
    }

    fun testOverrideParameterPreviewIsApplicableAndPreservesSource() = runBlocking {
        val result = ChangeSignatureTool().execute(project, parameterRequest(dryRun = true))
        assertToolSucceeded("A Kotlin override must be accepted for parameter preview", result)
        val preview = Json.parseToJsonElement(toolText(result)).jsonObject
        assertTrue(toolText(result), preview.getValue("canApply").jsonPrimitive.boolean)
        assertSource(source)
    }

    fun testOverrideParameterChangeUpdatesContractImplementationAndCall() = runBlocking {
        val result = ChangeSignatureTool().execute(project, parameterRequest())
        assertSource(source.replace("transform(value: Int)", "transform(value: Int, offset: Int)")
            .replace("transform(1)", "transform(1, 2)"))
        assertToolSucceeded("Kotlin parameter changes must preserve the interface contract", result)
    }

    fun testValidHandleForClassIsNotReportedAsExpired() = runBlocking {
        val handle = symbolHandle("Implementation")
        assertHandleUsable(handle)
        val result = ChangeSignatureTool().execute(project, renameRequest(symbolId = handle))
        assertToolFailed("A class is not a change-signature method target", result)
        assertFalse("Wrong target kind is not an expired handle: ${toolText(result)}", toolText(result).contains("SYMBOL_ID_EXPIRED"))
        assertHandleUsable(handle)
        assertSource(source)
    }

    fun testAbortedKotlinProcessorIsNotReportedAsSuccess() = runBlocking {
        val result = ChangeSignatureTool().apply { processorRunHook = {} }
            .execute(project, renameRequest())
        assertToolFailed("A processor that makes no change must not report success", result)
        assertTrue(toolText(result), toolText(result).contains("did not apply"))
        assertSource(source)
    }

    fun testOverrideHandleStaysBoundToImplementationAcrossPreviewAndApply() = runBlocking {
        val handle = symbolHandle("transform")
        val preview = ChangeSignatureTool().execute(project, parameterRequest(dryRun = true, symbolId = handle))
        assertToolSucceeded("An override handle must support parameter preview", preview)
        assertTrue(toolText(preview), Json.parseToJsonElement(toolText(preview)).jsonObject.getValue("canApply").jsonPrimitive.boolean)
        assertSource(source)
        assertHandleLine(handle, 8)

        val result = ChangeSignatureTool().execute(project, parameterRequest(symbolId = handle))
        assertToolSucceeded("An override handle must support applying its parameter change", result)
        assertSource(source.replace("transform(value: Int)", "transform(value: Int, offset: Int)")
            .replace("transform(1)", "transform(1, 2)"))
        assertHandleLine(handle, 8)
        val updated = Json.parseToJsonElement(toolText(result)).jsonObject.getValue("updatedSymbol").jsonObject
        assertEquals(handle, updated.getValue("symbolId").jsonPrimitive.content)
        assertEquals("8", updated.getValue("line").jsonPrimitive.content)
    }

    fun testReadOnlyBasePreviewReportsItsSourceFileAndCannotApply() = runBlocking {
        withReadOnlyContract {
            for (handle in listOf(null, symbolHandle("transform"))) {
                val result = ChangeSignatureTool().execute(project, parameterRequest(dryRun = true, symbolId = handle))
                assertToolSucceeded("A read-only base must still support preview", result)
                val preview = Json.parseToJsonElement(toolText(result)).jsonObject
                assertFalse(toolText(result), preview.getValue("canApply").jsonPrimitive.boolean)
                assertEquals("signature-src/Contract.kt", preview.getValue("target").jsonObject.getValue("file").jsonPrimitive.content)
                assertEquals(
                    listOf("signature-src/Contract.kt", file),
                    preview.getValue("affectedFiles").jsonArray.map { it.jsonPrimitive.content }
                )
                assertTrue(toolText(result), toolText(result).contains("read-only"))
            }
        }
    }

    fun testReadOnlyBaseApplyIsRejectedBeforeProcessor() = runBlocking {
        withReadOnlyContract {
            for (handle in listOf(null, symbolHandle("transform"))) {
                var processorEntered = false
                val result = ChangeSignatureTool().apply { processorRunHook = { processorEntered = true } }
                    .execute(project, parameterRequest(symbolId = handle))
                assertToolFailed("The actual base declaration must be writable", result)
                assertTrue(toolText(result), toolText(result).contains("read-only"))
                assertTrue(toolText(result), toolText(result).contains("Contract.kt"))
                assertFalse("Read-only base detection must precede the processor", processorEntered)
            }
        }
    }

    private suspend fun withReadOnlyContract(action: suspend () -> Unit) {
        val contract = "interface Contract {\n    fun transform(value: Int): Int\n}"
        val implementationSource = source.replace(contract, "\n\n")
        writeProjectFile(file, implementationSource)
        val contractSource = "package signatureprobe\n\n$contract"
        val contractPath = writeProjectFile("signature-src/Contract.kt", contractSource)
        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(contractPath.toString()))
        WriteAction.runAndWait<Throwable> { virtualFile.isWritable = false }
        try {
            action()
            assertSource(implementationSource)
            assertEquals(contractSource, Files.readString(contractPath))
        } finally {
            WriteAction.runAndWait<Throwable> { virtualFile.isWritable = true }
        }
    }

    private fun renameRequest(dryRun: Boolean = false, symbolId: String? = null): JsonObject = buildJsonObject {
        if (symbolId != null) put("symbolId", symbolId) else {
            put("file", file)
            put("line", 10)
            put("column", 9)
        }
        put("newName", "useRenamed")
        put("dryRun", dryRun)
    }

    private fun parameterRequest(dryRun: Boolean = false, symbolId: String? = null): JsonObject = buildJsonObject {
        if (symbolId != null) put("symbolId", symbolId) else {
            put("file", file)
            put("line", 8)
            put("column", 18)
        }
        put("dryRun", dryRun)
        put("newParameters", buildJsonArray {
            add(buildJsonObject { put("oldIndex", 0); put("name", "value"); put("type", "Int") })
            add(buildJsonObject { put("oldIndex", -1); put("name", "offset"); put("type", "Int"); put("defaultValue", "2") })
        })
    }

    private suspend fun symbolHandle(name: String): String {
        val (line, column) = when (name) {
            "Implementation" -> 7 to 7
            "transform" -> 8 to 18
            "use" -> 10 to 9
            else -> error("Unknown fixture declaration: $name")
        }
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", line)
            put("column", column)
            put("includeDoc", false)
        })
        assertToolSucceeded("Symbol info must expose a source handle", result)
        return Json.decodeFromString<SymbolInfoResult>(toolText(result)).symbolId
    }

    private suspend fun assertHandleUsable(handle: String) {
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("symbolId", handle)
            put("includeDoc", false)
        })
        assertToolSucceeded("The handle must remain valid for semantic tools", result)
    }

    private suspend fun assertHandleLine(handle: String, line: Int) {
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("symbolId", handle)
            put("includeDoc", false)
        })
        assertToolSucceeded("The original handle must remain bound to its declaration", result)
        assertEquals(line.toString(), Json.parseToJsonElement(toolText(result)).jsonObject.getValue("line").jsonPrimitive.content)
    }

    private fun assertSource(expected: String) {
        assertEquals(expected, Files.readString(Path.of(requireNotNull(project.basePath), file)))
    }
}
