package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

class KotlinReplaceMemberFormattingBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in test runtime must load Kotlin", PluginDetectors.kotlin.isAvailable)
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testBlockBodyWithoutTrailingNewline() = verifyBlockBody("return 21")

    fun testBlockBodyWithTrailingNewline() = verifyBlockBody("return 21\n")

    fun testExpressionBodyKeepsItsEqualsSign() = verifySimpleReplacement(
        before = "fun answer(): Int = 42",
        content = "7*3",
        expected = "fun answer(): Int = 7 * 3"
    )

    fun testPropertyInitializerRemainsAnExpression() = verifySimpleReplacement(
        before = "val answer: Int = 42",
        content = "7*3",
        expected = "val answer: Int = 7 * 3"
    )

    fun testEmptyBodyUsesKotlinCodeStyle() = verifySimpleReplacement(
        before = "fun answer() {\n    println(42)\n}",
        content = "",
        expected = "fun answer() {}"
    )

    fun testReformatFalsePreservesContentWithoutTrailingNewline() = verifySimpleReplacement(
        before = "fun answer(): Int {\n    return 42\n}",
        content = "return  21",
        expected = "fun answer(): Int {return  21}",
        reformat = false
    )

    fun testReformatFalsePreservesContentWithTrailingNewline() = verifySimpleReplacement(
        before = "fun answer(): Int {\n    return 42\n}",
        content = "return  21\n",
        expected = "fun answer(): Int {return  21\n}",
        reformat = false,
        endLine = 2
    )

    private fun verifySimpleReplacement(
        before: String,
        content: String,
        expected: String,
        reformat: Boolean = true,
        endLine: Int = 1
    ) = runBlocking {
        val file = "src/Probe.kt"
        val path = writeProjectFile(file, before)
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("member", "answer")
            put("content", content)
            put("reformat", reformat)
        })
        assertToolSucceeded("Replace the Kotlin body or initializer", result)
        assertEquals(expected, Files.readString(path))
        val payload = json.decodeFromString<MemberEditResult>(toolText(result))
        assertEquals(1, payload.startLine)
        assertEquals(endLine, payload.endLine)
        if (reformat) {
            assertFullReformatUnchanged(file, path, expected)
        }
    }

    private fun verifyBlockBody(content: String) = runBlocking {
        val file = "src/McpIdeSmokeProbe.kt"
        val path = writeProjectFile(file, """
            package probe

            internal class McpIdeSmokeProbe {
                fun answer(): Int {
                    return "intentional type mismatch for MCP smoke check"
                }

                fun doubled(): Int {
                    return answer() * 2
                }
            }
        """.trimIndent())
        assertTrue("The probe must not be open in an editor", FileEditorManager.getInstance(project).openFiles.isEmpty())

        val symbolInfo = SymbolInfoTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", 4)
            put("column", 13)
            put("includeDoc", false)
        })
        assertToolSucceeded("Discover the function handle", symbolInfo)
        val symbolId = json.decodeFromString<SymbolInfoResult>(toolText(symbolInfo)).symbolId

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            putJsonObject("target") { put("symbolId", symbolId) }
            put("content", content)
            put("reformat", true)
        })
        assertToolSucceeded("Replace the Kotlin block body", result)
        val expected = """
            package probe

            internal class McpIdeSmokeProbe {
                fun answer(): Int {
                    return 21
                }

                fun doubled(): Int {
                    return answer() * 2
                }
            }
        """.trimIndent()
        assertEquals("The saved file must include a newline before the closing brace", expected, Files.readString(path))
        val payload = json.decodeFromString<MemberEditResult>(toolText(result))
        assertEquals(4, payload.startLine)
        assertEquals(6, payload.endLine)
        assertEquals(symbolId, payload.updatedSymbol?.symbolId)

        assertFullReformatUnchanged(file, path, expected)
    }

    private suspend fun assertFullReformatUnchanged(file: String, path: Path, expected: String) {
        val reformatted = ReformatCodeTool().execute(project, buildJsonObject {
            put("file", file)
            put("optimizeImports", false)
            put("rearrangeCode", false)
        })
        assertToolSucceeded("Format the whole file", reformatted)
        assertEquals("Full-file formatting must not repair the replaced body", expected, Files.readString(path))
    }
}
