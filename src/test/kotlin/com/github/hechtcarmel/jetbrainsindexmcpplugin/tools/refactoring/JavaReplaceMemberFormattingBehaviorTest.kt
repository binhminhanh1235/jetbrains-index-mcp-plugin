package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files

class JavaReplaceMemberFormattingBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        val manager = CodeStyleSettingsManager.getInstance(project)
        val settings = manager.currentSettings.clone()
        settings.getCommonSettings(JavaLanguage.INSTANCE).KEEP_SIMPLE_METHODS_IN_ONE_LINE = false
        manager.setTemporarySettings(settings)
    }

    override fun tearDown() {
        try {
            CodeStyleSettingsManager.getInstance(project).dropTemporarySettings()
        } finally {
            super.tearDown()
        }
    }

    fun testBodyWithoutTrailingNewlineAndRemovedImport() = verifyBlockBody("return 21;")

    fun testBodyWithTrailingNewlineAndRemovedImport() = verifyBlockBody("return 21;\n")

    private fun verifyBlockBody(content: String) = runBlocking {
        writeProjectFile("src/probe/Helper.java", "package probe; public class Helper {}")
        registerSourceRoot("src")
        val file = "src/probe/Probe.java"
        val path = writeProjectFile(file, """
            package probe;

            import probe.Helper;

            public class Probe {
                public int answer() {
                    return 42;
                }

                public int doubled() {
                    return answer() * 2;
                }
            }
        """.trimIndent())

        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("member", "answer")
            put("content", content)
            put("reformat", true)
        })
        assertToolSucceeded("Replace and format the Java method body", result)
        val expected = """
            package probe;

            public class Probe {
                public int answer() {
                    return 21;
                }

                public int doubled() {
                    return answer() * 2;
                }
            }
        """.trimIndent()
        assertEquals("The saved file must be formatted and the redundant import removed", expected, Files.readString(path))
        val payload = json.decodeFromString<MemberEditResult>(toolText(result))
        assertEquals("Body start follows import removal", 4, payload.startLine)
        assertEquals("Body end follows inserted newlines and import removal", 6, payload.endLine)

        val reformatted = ReformatCodeTool().execute(project, buildJsonObject {
            put("file", file)
            put("optimizeImports", false)
            put("rearrangeCode", false)
        })
        assertToolSucceeded("Format the whole Java file", reformatted)
        assertEquals("Full formatting must leave the method and its sibling unchanged", expected, Files.readString(path))
    }

    fun testReformatFalsePreservesInsertedWhitespace() = runBlocking {
        val path = writeProjectFile("src/Raw.java", "class Raw { int answer() { return 42; } }")
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", "src/Raw.java")
            put("member", "answer")
            put("content", "return  21;")
            put("reformat", false)
        })
        assertToolSucceeded("Replace without formatting", result)
        assertEquals("class Raw { int answer() {return  21;} }", Files.readString(path))
    }
}
