package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase

class SymbolDocumentationUnitTest : TestCase() {

    fun testNormalizeSignatureStripsHtmlAndEmptyLines() {
        val html = """
            <html>
                <body>
                    <b>public class</b> Foo<br>
                    <p>&nbsp;&nbsp;fun bar():&nbsp;String</p>
                </body>
            </html>
        """.trimIndent()

        val result = SymbolDocumentation.normalizeSignature(html)
        assertNotNull(result)
        assertTrue(result!!.contains("public class Foo"))
        assertTrue(result.contains("fun bar(): String"))
        // Empty lines should be dropped
        assertFalse(result.contains("\n\n"))
    }

    fun testNormalizeSignatureReturnsNullForBlank() {
        val html = "<html><body>   <p></p>   </body></html>"
        val result = SymbolDocumentation.normalizeSignature(html)
        assertNull(result)
    }

    fun testNormalizeDocCollapsesExcessiveBlankLines() {
        val html = """
            <html>
                <body>
                    <p>Paragraph 1</p>
                    <br><br><br><br>
                    <p>Paragraph 2</p>
                </body>
            </html>
        """.trimIndent()

        val result = SymbolDocumentation.normalizeDoc(html)
        assertNotNull(result)
        assertEquals("Paragraph 1\n\nParagraph 2", result)
    }

    fun testNormalizeDocHandlesNonBreakingSpaceAndCrLf() {
        val html = "Hello&nbsp;World<br><br>\r\nFoo&nbsp;Bar"
        val result = SymbolDocumentation.normalizeDoc(html)
        assertNotNull(result)
        assertEquals("Hello World\n\nFoo Bar", result)
    }

    fun testNormalizeDocReturnsNullForBlank() {
        val html = "<p>   </p>"
        val result = SymbolDocumentation.normalizeDoc(html)
        assertNull(result)
    }
}
