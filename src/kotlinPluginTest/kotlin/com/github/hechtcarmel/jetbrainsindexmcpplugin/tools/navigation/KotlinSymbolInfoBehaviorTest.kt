package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.SignatureSources
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

/** Exercises the actual source-text fallback with the bundled Kotlin plugin. */
class KotlinSymbolInfoBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load the Kotlin plugin", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("signature-src")
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testAnnotatedMethodReturnsDeclarationInsteadOfTestAnnotation() = assertSignature(
        """
        annotation class Test
        class FormattingTest {
            @Test
            fun <caret>formatNumber() {}
        }
        """.trimIndent(),
        "fun formatNumber()"
    )

    fun testKdocAndAnnotationsDoNotReplaceDeclaration() = assertSignature(
        """
        annotation class Test
        class FormattingTest {
            /**
             * Checks formatNumber rather than exposing this comment as a signature.
             */
            @Test
            private fun <caret>formatNumber(value: Int): Int { return value }
        }
        """.trimIndent(),
        "private fun formatNumber(value: Int): Int"
    )

    fun testMultilineAnnotationContainingMethodNameDoesNotConfuseDeclarationLookup() = assertSignature(
        """
        annotation class Example(val message: String)
        class FormattingTest {
            @Example(
                "formatNumber { is mentioned in this annotation }"
            )
            internal fun <caret>formatNumber(value: Int): Int { return value }
        }
        """.trimIndent(),
        "internal fun formatNumber(value: Int): Int"
    )

    fun testInlineAnnotationBraceDoesNotTruncateMethodDeclaration() = assertSignature(
        """
        annotation class Example(val message: String)
        class FormattingTest {
            @Example("formatNumber {") fun <caret>formatNumber() {}
        }
        """.trimIndent(),
        "@Example(\"formatNumber {\") fun formatNumber()"
    )

    fun testBraceInsideQuotedMethodNameDoesNotTruncateDeclaration() = assertSignature(
        """
        annotation class Test
        class FormattingTest {
            @Test
            fun `<caret>formats { numbers`() {}
        }
        """.trimIndent(),
        "fun `formats { numbers`()"
    )

    fun testUnannotatedFunctionKeepsSourceLineFallback() = assertSignature(
        """
        fun <caret>formatNumber(value: Int): Int { return value }
        """.trimIndent(),
        "fun formatNumber(value: Int): Int"
    )

    private fun assertSignature(markedSource: String, expected: String) = runBlocking {
        val offset = markedSource.indexOf("<caret>")
        require(offset >= 0)
        val source = markedSource.replace("<caret>", "")
        val file = "signature-src/FormattingTest.kt"
        val path = writeProjectFile(file, source)
        val result = SymbolInfoTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", source.take(offset).count { it == '\n' } + 1)
            put("column", offset - source.lastIndexOf('\n', offset - 1))
            put("includeDoc", false)
        })
        assertToolSucceeded("The Kotlin symbol must be described", result)
        val info = Json.decodeFromString<SymbolInfoResult>(toolText(result))
        assertEquals("The regression must execute the source-text fallback", SignatureSources.ELEMENT_TEXT, info.signatureSource)
        assertEquals(expected, info.signature)
        assertTrue("The signature must include the declaration name", info.signature.contains(info.name))
        assertNull("Source text does not promise semantically resolved parameters", info.parameters)
        assertNull("Source text does not promise a semantically resolved return type", info.returnType)
        assertEquals("Symbol inspection must preserve the file", source, Files.readString(path))
    }
}
