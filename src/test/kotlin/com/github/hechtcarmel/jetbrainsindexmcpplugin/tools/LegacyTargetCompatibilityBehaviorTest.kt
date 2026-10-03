package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.CallHierarchyTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ChangeSignatureTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.EditMemberTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceMemberTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.SafeDeleteTool
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The relaxed schema requirements allow alternative selectors, while old requests still work. */
class LegacyTargetCompatibilityBehaviorTest : McpPlatformTestCase() {
    private val file = "legacy-src/LegacyClient.java"
    private val source = """
        class LegacyClient {
            int value() { return 1; }
            int caller() { return value(); }
            int outer() { return caller(); }
            void obsolete() {}
        }
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("legacy-src")
        writeProjectFile(file, source)
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testLegacyRenamePositionUpdatesDeclarationAndCaller() = runBlocking {
        val result = RenameSymbolTool().execute(project, buildJsonObject {
            position("value")
            put("newName", "renamed")
        })
        assertToolSucceeded("legacy rename", result)
        assertContains("int renamed()", "return renamed();", "int outer()")
        assertFileDoesNotContain(file, "value()")
    }

    fun testLegacySafeDeletePositionKeepsOtherDeclarations() = runBlocking {
        val result = SafeDeleteTool().execute(project, buildJsonObject { position("obsolete") })
        assertToolSucceeded("legacy safe delete", result)
        assertFileDoesNotContain(file, "obsolete")
        assertContains("int value()", "return value();")
    }

    fun testLegacyChangeSignaturePositionUpdatesCaller() = runBlocking {
        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            position("value")
            put("newName", "changed")
        })
        assertToolSucceeded("legacy change signature", result)
        assertContains("int changed()", "return changed();", "int outer()")
        assertFileDoesNotContain(file, "value()")
    }

    fun testLegacyEditMemberUsesFileAndName() = runBlocking {
        val result = EditMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("member", "value")
            put("content", "int value() { return 2; }")
            put("reformat", false)
        })
        assertToolSucceeded("legacy member edit", result)
        assertContains("return 2;", "return value();", "void obsolete()")
        assertFileDoesNotContain(file, "return 1;")
    }

    fun testLegacyReplaceMemberUsesFileAndName() = runBlocking {
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("file", file)
            put("member", "value")
            put("content", "return 3;")
            put("reformat", false)
        })
        assertToolSucceeded("legacy member body replacement", result)
        assertContains("return 3;", "return value();", "void obsolete()")
        assertFileDoesNotContain(file, "return 1;")
    }

    fun testLegacyCallHierarchyDirectionAndDepthReturnNestedCallers() = runBlocking {
        val result = CallHierarchyTool().execute(project, buildJsonObject {
            position("value")
            put("direction", "callers")
            put("depth", 2)
        })
        assertToolSucceeded("legacy hierarchy", result)
        val hierarchy = Json { ignoreUnknownKeys = true }.decodeFromString<CallHierarchyResult>(toolText(result))
        val caller = hierarchy.calls.single { it.name.contains("caller") }
        assertTrue("depth=2 must retain nested callers", caller.children.orEmpty().any { it.name.contains("outer") })
    }

    fun testRelaxedSchemasStillRejectRequestsWithoutAnyTarget() = runBlocking {
        val path = java.nio.file.Path.of(requireNotNull(project.basePath)).resolve(file)
        val before = Files.readAllBytes(path)
        val requests = listOf(
            RenameSymbolTool() to buildJsonObject { put("newName", "changed") },
            SafeDeleteTool() to buildJsonObject { put("force", true) },
            ChangeSignatureTool() to buildJsonObject { put("newName", "changed") },
            EditMemberTool() to buildJsonObject { put("content", "int value() { return 2; }") },
            ReplaceMemberTool() to buildJsonObject { put("content", "return 3;") },
            CallHierarchyTool() to buildJsonObject { put("direction", "callers") }
        )
        for ((tool, arguments) in requests) {
            assertToolFailed("${tool.name} needs a target despite optional schema fields", tool.execute(project, arguments))
            assertTrue("${tool.name} changed files without a target", before.contentEquals(Files.readAllBytes(path)))
        }
    }

    private fun assertContains(vararg fragments: String) {
        fragments.forEach { assertFileContains(file, it) }
    }

    private fun JsonObjectBuilder.position(marker: String) {
        val offset = source.indexOf(marker)
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        put("file", file)
        put("line", source.substring(0, offset).count { it == '\n' } + 1)
        put("column", offset - lineStart + 1)
    }
}
