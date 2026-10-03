package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

class RenamePreviewTargetBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testClassSymbolRenameDetectsExistingEmptyDestinationFile() = runBlocking {
        registerSourceRoot("r2-class-collision")
        val original = writeProjectFile("r2-class-collision/A.java", "public class A {}")
        val occupied = writeProjectFile("r2-class-collision/B.java", "// reserved file")
        val preview = payload(RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "r2-class-collision/A.java")
            put("line", 1)
            put("column", 14)
            put("newName", "B")
            put("relatedRenamingStrategy", "none")
            put("dryRun", true)
        }))
        assertEquals("public class A {}", Files.readString(original))
        assertEquals("// reserved file", Files.readString(occupied))
        assertFalse("Preview must detect implicit A.java -> B.java collision: $preview", preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(preview.getValue("conflictCount").jsonPrimitive.int > 0)
    }

    fun testConstructorRenamePreviewIncludesClassTypeUsages() = runBlocking {
        registerSourceRoot("r2-constructor")
        val original = writeProjectFile("r2-constructor/Foo.java", "public class Foo { public Foo() {} }")
        val caller = writeProjectFile("r2-constructor/TypeOnly.java", "class TypeOnly { Foo field; }")
        val preview = payload(RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "r2-constructor/Foo.java")
            put("line", 1)
            put("column", 27)
            put("newName", "Bar")
            put("relatedRenamingStrategy", "none")
            put("dryRun", true)
        }))
        assertEquals("public class Foo { public Foo() {} }", Files.readString(original))
        assertEquals("class TypeOnly { Foo field; }", Files.readString(caller))
        assertTrue("Ordinary constructor/class rename should be applicable: $preview", preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue("Constructor apply renames the class, so the preview must contain its type-only caller: $preview",
            preview.getValue("affectedFiles").jsonArray.any { it.jsonPrimitive.content == "r2-constructor/TypeOnly.java" })
    }

    fun testFileRenameDetectsDestinationDirectory() = runBlocking {
        val original = writeProjectFile("r2-directory-collision/Original.txt", "original")
        writeProjectFile("r2-directory-collision/Target.txt/keep.txt", "keep")
        val preview = payload(RenameSymbolTool().execute(project, buildJsonObject {
            put("file", "r2-directory-collision/Original.txt")
            put("newName", "Target.txt")
            put("relatedRenamingStrategy", "none")
            put("dryRun", true)
        }))
        assertEquals("original", Files.readString(original))
        assertTrue(Files.isDirectory(Path.of(project.basePath!!, "r2-directory-collision/Target.txt")))
        assertFalse("A directory occupies the destination filename: $preview", preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(preview.getValue("conflictCount").jsonPrimitive.int > 0)
    }

    fun testNestedPositionSelectorFlowsThroughRenamePreview() = runBlocking {
        registerSourceRoot("r2-nested-position")
        val source = """
            package previewselector;

            public class NestedTarget {
                public void work() {}
            }
        """.trimIndent()
        val original = writeProjectFile("r2-nested-position/previewselector/NestedTarget.java", source)

        val preview = payload(RenameSymbolTool().execute(project, buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "r2-nested-position/previewselector/NestedTarget.java")
                    put("line", 4)
                    put("column", 17)
                }
            }
            put("newName", "execute")
            put("relatedRenamingStrategy", "none")
            put("dryRun", true)
        }))

        assertTrue(
            "Nested position preview should be applicable: $preview",
            preview.getValue("canApply").jsonPrimitive.boolean
        )
        assertEquals("work", preview.getValue("target").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(source, Files.readString(original))
    }

    fun testLegacyQualifiedSelectorFlowsThroughRenamePreview() = runBlocking {
        registerSourceRoot("r2-qualified-selector")
        val source = """
            package previewselector;

            public class QualifiedTarget {
                public void work() {}
            }
        """.trimIndent()
        val original = writeProjectFile("r2-qualified-selector/previewselector/QualifiedTarget.java", source)

        val preview = payload(RenameSymbolTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "previewselector.QualifiedTarget#work()")
            put("newName", "execute")
            put("relatedRenamingStrategy", "none")
            put("dryRun", true)
        }))

        assertTrue(
            "Qualified selector preview should be applicable: $preview",
            preview.getValue("canApply").jsonPrimitive.boolean
        )
        assertEquals("work", preview.getValue("target").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(source, Files.readString(original))
    }

    private fun payload(result: CallToolResult): JsonObject {
        assertToolSucceeded("Preview returns structured result", result)
        val text = toolText(result)
        return Json.parseToJsonElement(text).jsonObject
    }
}
