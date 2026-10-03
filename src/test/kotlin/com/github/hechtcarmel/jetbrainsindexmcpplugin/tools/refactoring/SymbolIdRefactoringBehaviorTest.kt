package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ResolvedSymbolInfo
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindDefinitionTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.SymbolInfoTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class SymbolIdRefactoringBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
        } finally {
            super.tearDown()
        }
    }

    fun testChangeSignatureByIdReturnsReboundMetadataAndUpdatesCaller() = runBlocking {
        registerSourceRoot("signature-id-src")
        val declaration = """
            package signatureid;
            class Service {
                int calculate(int input) { return input; }
            }
        """.trimIndent()
        writeProjectFile("signature-id-src/signatureid/Service.java", declaration)
        writeProjectFile(
            "signature-id-src/signatureid/Caller.java",
            """
            package signatureid;
            class Caller {
                int call(Service service) { return service.calculate(1); }
            }
            """.trimIndent()
        )
        val definition = definitionAt("signature-id-src/signatureid/Service.java", declaration, "calculate")

        val result = ChangeSignatureTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("newName", "compute")
        })
        assertToolSucceeded("change_signature should accept symbolId", result)
        val payload = json.parseToJsonElement(toolText(result)).jsonObject
        val updated = json.decodeFromJsonElement<ResolvedSymbolInfo>(payload.getValue("updatedSymbol"))
        assertEquals(definition.symbolId, updated.symbolId)
        assertEquals("compute", updated.name)
        assertRenamedInFile("signature-id-src/signatureid/Service.java", "calculate", "compute")
        assertRenamedInFile("signature-id-src/signatureid/Caller.java", "calculate", "compute")
    }

    fun testEditAndReplaceMemberByIdKeepHandleOnTheEditedDeclaration() = runBlocking {
        val source = """
            class EditableById {
                int value() { return 1; }
            }
        """.trimIndent()
        writeProjectFile("member-id-src/EditableById.java", source)
        val definition = definitionAt("member-id-src/EditableById.java", source, "value")

        val editResult = EditMemberTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("content", "long renamedValue() { return 2L; }")
            put("reformat", false)
        })
        assertToolSucceeded("edit_member should accept symbolId", editResult)
        val edit = decode<MemberEditResult>(editResult)
        assertEquals(definition.symbolId, edit.updatedSymbol?.symbolId)
        assertEquals("renamedValue", edit.updatedSymbol?.name)
        assertFileContains("member-id-src/EditableById.java", "long renamedValue() { return 2L; }")
        assertFileDoesNotContain("member-id-src/EditableById.java", "int value()")

        val replaceResult = ReplaceMemberTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("content", "return 3L;")
            put("reformat", false)
        })
        assertToolSucceeded("replace_member should reuse the rebound symbolId", replaceResult)
        val replace = decode<MemberEditResult>(replaceResult)
        assertEquals(definition.symbolId, replace.updatedSymbol?.symbolId)
        assertEquals("renamedValue", replace.updatedSymbol?.name)
        assertFileContains("member-id-src/EditableById.java", "return 3L;")
        assertFileDoesNotContain("member-id-src/EditableById.java", "return 2L;")

        val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolSucceeded("Edited member ID should remain usable", lookup)
        assertTrue(toolText(lookup).contains("renamedValue"))
    }

    fun testNestedPositionEditsTheExactMemberWithoutLegacyMemberSelector() = runBlocking {
        val source = """
            class PositionTarget {
                int first() { return 1; }
                int second() { return 2; }
            }
        """.trimIndent()
        val file = "member-position-src/PositionTarget.java"
        writeProjectFile(file, source)

        val editResult = EditMemberTool().execute(
            project,
            nestedPositionArguments(file, source, "second()", "long renamedSecond() { return 20L; }")
        )
        assertToolSucceeded("edit_member nested position should identify second exactly", editResult)
        assertFileContains(file, "int first() { return 1; }")
        assertFileContains(file, "long renamedSecond() { return 20L; }")

        val edited = readProjectFileVfs(file)
        val replaceResult = ReplaceMemberTool().execute(
            project,
            nestedPositionArguments(file, edited, "renamedSecond()", "return 30L;")
        )
        assertToolSucceeded("replace_member nested position should identify renamed member exactly", replaceResult)
        assertFileContains(file, "int first() { return 1; }")
        assertFileContains(file, "return 30L;")
    }

    fun testInvalidFullReplacementsLeaveSourceAndOriginalMethodHandleUnchanged() = runBlocking {
        val source = """
            class RejectInvalidReplacement {
                int original() { return 1; }
                int survivor() { return 2; }
            }
        """.trimIndent()
        val file = "member-invalid-src/RejectInvalidReplacement.java"
        writeProjectFile(file, source)
        val original = definitionAt(file, source, "original()")
        val invalidReplacements = listOf(
            "/* removed */",
            "// removed",
            "int first() { return 3; } int second() { return 4; }",
            "class Nested {}",
            "RejectInvalidReplacement() {}",
            "int incomplete( { return 3; }",
            "int renamed() { return 3; } /* unterminated"
        )

        for (replacement in invalidReplacements) {
            val result = EditMemberTool().execute(project, buildJsonObject {
                put("symbolId", original.symbolId)
                put("content", replacement)
                put("reformat", false)
            })
            assertToolFailed("Invalid replacement must be rejected before editing: $replacement", result)
            assertTrue(toolText(result).contains("exactly one complete method declaration"))
            assertEquals("A rejected edit must preserve every byte: $replacement", source, readProjectFileVfs(file))

            val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", original.symbolId) })
            assertToolSucceeded("Rejected edit must retain the original method handle: $replacement", lookup)
            val symbol = decode<SymbolInfoResult>(lookup)
            assertEquals(original.symbolId, symbol.symbolId)
            assertEquals("original", symbol.name)
        }
    }

    fun testCommentedAnnotatedReplacementKeepsExactHandleThroughFormattingAndNestedDeclarations() = runBlocking {
        val source = """
            class AnnotatedReplacement {
                int original() { return 1; }
                int survivor() { return 2; }
            }
        """.trimIndent()
        val file = "member-comments-src/AnnotatedReplacement.java"
        writeProjectFile(file, source)
        val original = definitionAt(file, source, "original()")
        val replacement = """
            /* before the declaration */
            /** Updated implementation. */
            @Deprecated
            long renamed(int input) {
                class Local { long nested() { return 3L; } }
                return new Local().nested() + input;
            }
            /* after the declaration */
        """.trimIndent()

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
            put("content", replacement)
            put("reformat", true)
        })
        assertToolSucceeded("Comments and nested declarations must not hide the exact replacement", result)
        val edit = decode<MemberEditResult>(result)
        assertEquals(original.symbolId, edit.updatedSymbol?.symbolId)
        assertEquals("renamed", edit.updatedSymbol?.name)
        assertFileContains(file, "before the declaration")
        assertFileContains(file, "Updated implementation.")
        assertFileContains(file, "@Deprecated")
        assertFileContains(file, "class Local")
        assertFileContains(file, "after the declaration")
        assertFileContains(file, "int survivor()")
        assertFileDoesNotContain(file, "int original()")

        val replaceResult = ReplaceMemberTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
            put("content", "return 40L;")
            put("reformat", false)
        })
        assertToolSucceeded("The rebound handle must edit the outer replacement method", replaceResult)
        assertFileContains(file, "long renamed(int input)")
        assertFileContains(file, "return 40L;")
        assertFileContains(file, "int survivor()")
        assertFileDoesNotContain(file, "class Local")
    }

    fun testFullClassReplacementKeepsClassHandleAcrossInterfaceConversion() = runBlocking {
        val source = """
            class OriginalClass {
                int value() { return 1; }
            }
            class UnchangedSibling {}
        """.trimIndent()
        val file = "member-class-id-src/OriginalClass.java"
        writeProjectFile(file, source)
        val original = definitionAt(file, source, "OriginalClass")

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
            put("content", "/** Replacement type. */ interface RenamedType<T> { T value(); class Nested {} }")
            put("reformat", false)
        })
        assertToolSucceeded("A class declaration may be replaced by an interface with nested members", result)
        val edit = decode<MemberEditResult>(result)
        assertEquals(original.symbolId, edit.updatedSymbol?.symbolId)
        assertEquals("RenamedType", edit.updatedSymbol?.name)
        assertFileContains(file, "interface RenamedType<T>")
        assertFileContains(file, "class Nested")
        assertFileContains(file, "class UnchangedSibling {}")
        assertFileDoesNotContain(file, "class OriginalClass")

        val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", original.symbolId) })
        assertToolSucceeded("Converted class handle must identify the replacement interface", lookup)
        assertEquals("RenamedType", decode<SymbolInfoResult>(lookup).name)
    }

    fun testFullFieldReplacementKeepsHandleOnRenamedField() = runBlocking {
        val source = """
            class FieldReplacement {
                int original = 1;
                int survivor = 2;
            }
        """.trimIndent()
        val file = "member-field-id-src/FieldReplacement.java"
        writeProjectFile(file, source)
        val original = definitionAt(file, source, "original")

        for (replacement in listOf("long first = 3L, second = 4L;", "long first = 3L; long second = 4L;")) {
            val rejected = EditMemberTool().execute(project, buildJsonObject {
                put("symbolId", original.symbolId)
                put("content", replacement)
                put("reformat", false)
            })
            assertToolFailed("A single field ID must not be rebound to several replacement fields", rejected)
            assertEquals(source, readProjectFileVfs(file))
        }

        val result = EditMemberTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
            put("content", "/** Replaced field. */ @Deprecated long renamed = 3L; /* preserved */")
            put("reformat", false)
        })
        assertToolSucceeded("A field replacement should preserve exact identity", result)
        val edit = decode<MemberEditResult>(result)
        assertEquals(original.symbolId, edit.updatedSymbol?.symbolId)
        assertEquals("renamed", edit.updatedSymbol?.name)

        val replaceResult = ReplaceMemberTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
            put("content", "4L")
            put("reformat", false)
        })
        assertToolSucceeded("The field handle must select its replacement initializer", replaceResult)
        assertFileContains(file, "long renamed = 4L;")
        assertFileContains(file, "int survivor = 2;")
        assertFileContains(file, "/* preserved */")
        assertFileDoesNotContain(file, "int original")
    }

    fun testSafeDeleteByIdReturnsInvalidatedIdAndFurtherLookupsExpire() = runBlocking {
        registerSourceRoot("safe-delete-id-src")
        val source = """
            class SafeDeleteById {
                void doomed() {}
                void survivor() {}
            }
        """.trimIndent()
        writeProjectFile("safe-delete-id-src/SafeDeleteById.java", source)
        val definition = definitionAt("safe-delete-id-src/SafeDeleteById.java", source, "doomed")

        val previewResult = SafeDeleteTool().execute(project, buildJsonObject {
            putJsonObject("target") { put("symbolId", definition.symbolId) }
            put("force", true)
            put("dryRun", true)
        })
        assertToolSucceeded("safe_delete preview should accept nested target.symbolId", previewResult)
        val preview = decode<RefactoringPreviewResult>(previewResult)
        assertTrue(preview.canApply)
        assertEquals(definition.symbolId, preview.target.symbolId)
        assertFileContains("safe-delete-id-src/SafeDeleteById.java", "doomed")
        assertToolSucceeded(
            "A preview must leave the target handle valid",
            SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        )

        val deleteResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("force", true)
        })
        assertToolSucceeded("safe_delete should accept symbolId", deleteResult)
        val deleted = decode<RefactoringResult>(deleteResult)
        assertEquals(definition.symbolId, deleted.invalidatedSymbolId)
        assertFileDoesNotContain("safe-delete-id-src/SafeDeleteById.java", "doomed")
        assertFileContains("safe-delete-id-src/SafeDeleteById.java", "survivor")

        val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolFailed("Safe-deleted symbol ID must be invalidated", lookup)
        assertTrue(toolText(lookup).contains("SYMBOL_ID_EXPIRED"))
    }

    private suspend fun definitionAt(file: String, source: String, marker: String): DefinitionResult {
        val result = FindDefinitionTool().execute(project, positionArguments(file, source, marker))
        assertToolSucceeded("find_definition at $file / $marker", result)
        return decode(result)
    }

    private fun positionArguments(file: String, source: String, marker: String): JsonObject {
        val offset = source.indexOf(marker)
        require(offset >= 0) { "Marker '$marker' is absent from fixture" }
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        return buildJsonObject {
            put("file", file)
            put("line", source.substring(0, offset).count { it == '\n' } + 1)
            put("column", offset - lineStart + 1)
        }
    }

    private fun nestedPositionArguments(
        file: String,
        source: String,
        marker: String,
        content: String
    ): JsonObject {
        val position = positionArguments(file, source, marker)
        return buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    position.forEach { (key, value) -> put(key, value) }
                }
            }
            put("content", content)
            put("reformat", false)
        }
    }

    private inline fun <reified T> decode(result: CallToolResult): T =
        json.decodeFromString(toolText(result))
}
