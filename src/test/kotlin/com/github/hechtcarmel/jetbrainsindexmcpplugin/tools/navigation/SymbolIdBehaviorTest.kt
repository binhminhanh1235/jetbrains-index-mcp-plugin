package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindUsagesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SyncFilesTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SymbolIdBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
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

    fun testSuperMethodLookupDoesNotRebindAParameterHandle() = runBlocking {
        registerSourceRoot("parameter-handle-src")
        val file = "parameter-handle-src/ParameterOwner.java"
        val source = "class ParameterOwner { void work(int count) {} }"
        writeProjectFile(file, source)
        val parameter = definitionAt(file, source, "count")
        assertEquals("count", parameter.symbolName)

        val superMethods = FindSuperMethodsTool().execute(project, buildJsonObject { put("symbolId", parameter.symbolId) })
        assertToolSucceeded("Find the enclosing method's hierarchy from a parameter", superMethods)

        val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", parameter.symbolId) })
        assertToolSucceeded("a read-only super-method query must retain the original handle", lookup)
        val info = decode<SymbolInfoResult>(lookup)
        assertEquals("the parameter handle must still identify the parameter", "count", info.name)
    }

    fun testFindSymbolReturnsDistinctIdsForOverloadsAndSymbolInfoUsesExactOverload() = runBlocking {
        registerSourceRoot("overload-symbol-src")
        writeProjectFile(
            "overload-symbol-src/Overloaded.java",
            """
            class Overloaded {
                int choose(int value) { return value; }
                long choose(long value) { return value; }
            }
            """.trimIndent()
        )

        val search = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "choose")
            put("pageSize", 50)
        })
        assertToolSucceeded("find_symbol should find both overloads", search)
        val matches = decode<FindSymbolResult>(search).symbols
            .filter { it.file.endsWith("Overloaded.java") && it.name == "choose" }
        assertEquals("Both overloads must be returned", 2, matches.size)
        assertEquals("Overloads need separate opaque handles", 2, matches.map { it.symbolId }.toSet().size)
        assertTrue(matches.all { it.symbolId.startsWith("sym_") })

        val parameterTypes = matches.map { match ->
            val infoResult = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", match.symbolId) })
            assertToolSucceeded("symbol_info should resolve ${match.symbolId}", infoResult)
            val info = decode<SymbolInfoResult>(infoResult)
            assertEquals("A semantic round-trip should preserve the handle", match.symbolId, info.symbolId)
            requireNotNull(info.parameters).single().type
        }.toSet()

        assertEquals(setOf("int", "long"), parameterTypes)
    }

    fun testPaginatedSearchBindsOnlyReturnedSymbolsAndRebindsAnEvictedPageHandle() = runBlocking {
        registerSourceRoot("paged-symbol-src")
        writeProjectFile(
            "paged-symbol-src/PagedHandles.java",
            """
            class PagedHandles {
                void uniquelyPagedHandle(int value) {}
                void uniquelyPagedHandle(long value) {}
                void uniquelyPagedHandle(double value) {}
            }
            """.trimIndent()
        )
        val registry = SymbolIdRegistry.getInstance()

        val firstResult = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "uniquelyPagedHandle")
            put("pageSize", 1)
        })
        assertToolSucceeded("First symbol page should succeed", firstResult)
        val firstPage = decode<FindSymbolResult>(firstResult)
        assertEquals(1, firstPage.symbols.size)
        assertEquals(
            "Over-collected but undisclosed results must not consume symbol handles",
            1,
            registry.sizeForTest()
        )
        val secondCursor = requireNotNull(firstPage.nextCursor)

        val secondResult = FindSymbolTool().execute(project, buildJsonObject {
            put("cursor", secondCursor)
        })
        assertToolSucceeded("Second symbol page should succeed", secondResult)
        val secondPage = decode<FindSymbolResult>(secondResult)
        val originalSecondId = secondPage.symbols.single().symbolId
        assertTrue(originalSecondId.startsWith("sym_"))

        registry.invalidate(originalSecondId)
        val retriedResult = FindSymbolTool().execute(project, buildJsonObject {
            put("cursor", secondCursor)
        })
        assertToolSucceeded("Retrying the page should rebind an evicted handle", retriedResult)
        val reboundId = decode<FindSymbolResult>(retriedResult).symbols.single().symbolId
        assertFalse("A missing cached handle must be replaced", originalSecondId == reboundId)

        val infoResult = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", reboundId) })
        assertToolSucceeded("The replacement handle must be immediately resolvable", infoResult)
    }

    fun testLocalSymbolSurvivesExternalLineShiftAndDrivesUsagesAndRename() = runBlocking {
        registerSourceRoot("local-symbol-src")
        val original = """
            class LocalSymbolTarget {
                void run() {
                    int count = 1;
                    int copy = count;
                }
            }
        """.trimIndent()
        val path = writeProjectFile("local-symbol-src/LocalSymbolTarget.java", original)

        val definition = definitionAt("local-symbol-src/LocalSymbolTarget.java", original, "count;")
        assertEquals("count", definition.symbolName)
        assertTrue(definition.symbolId.startsWith("sym_"))

        Files.writeString(path, "// external header\n\n$original")
        val sync = SyncFilesTool().execute(project, buildJsonObject {})
        assertToolSucceeded("External rewrite should synchronize", sync)

        val usagesResult = FindUsagesTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
        })
        assertToolSucceeded("find_references should resolve the shifted local by ID", usagesResult)
        val usages = decode<FindUsagesResult>(usagesResult)
        assertEquals(1, usages.usages.size)
        assertEquals(definition.symbolId, usages.resolvedSymbol?.symbolId)
        assertEquals("count", usages.resolvedSymbol?.name)
        assertEquals("Smart pointer must follow the two inserted lines", 5, usages.resolvedSymbol?.line)

        val renameResult = RenameSymbolTool().execute(project, buildJsonObject {
            put("symbolId", definition.symbolId)
            put("newName", "total")
        })
        assertToolSucceeded("Local rename by ID should succeed", renameResult)
        val rename = decode<RefactoringResult>(renameResult)
        assertEquals("The live ID should be rebound after rename", definition.symbolId, rename.updatedSymbol?.symbolId)
        assertEquals("total", rename.updatedSymbol?.name)
        assertEquals(5, rename.updatedSymbol?.line)
        assertFileContains("local-symbol-src/LocalSymbolTarget.java", "int total = 1;")
        assertFileContains("local-symbol-src/LocalSymbolTarget.java", "int copy = total;")
        assertFileDoesNotContain("local-symbol-src/LocalSymbolTarget.java", "count")

        val infoResult = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolSucceeded("The same ID should resolve after rename", infoResult)
        val info = decode<SymbolInfoResult>(infoResult)
        assertEquals(definition.symbolId, info.symbolId)
        assertEquals("total", info.name)
    }

    fun testRemovingDeclarationExpiresIdInsteadOfRetargetingNearbyMethod() = runBlocking {
        registerSourceRoot("removed-symbol-src")
        val original = """
            class RemovedSymbolTarget {
                void removed() {}
                void survivor() {}
            }
        """.trimIndent()
        val path = writeProjectFile("removed-symbol-src/RemovedSymbolTarget.java", original)
        val definition = definitionAt("removed-symbol-src/RemovedSymbolTarget.java", original, "removed")

        Files.writeString(
            path,
            """
            class RemovedSymbolTarget {
                void survivor() {}
            }
            """.trimIndent()
        )
        assertToolSucceeded("Deletion should synchronize", SyncFilesTool().execute(project, buildJsonObject {}))

        val result = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolFailed("Deleted declaration ID must not snap to survivor", result)
        assertTrue(toolText(result).contains("SYMBOL_ID_EXPIRED"))
        assertFalse(toolText(result).contains("survivor"))
    }

    fun testDeletingFileExternallyExpiresItsSymbolIdsAfterSync() = runBlocking {
        val source = "class DeletedFileTarget { void target() {} }"
        val path = writeProjectFile("deleted-symbol-src/DeletedFileTarget.java", source)
        val definition = definitionAt("deleted-symbol-src/DeletedFileTarget.java", source, "target()")

        Files.delete(path)
        assertToolSucceeded("External file deletion should synchronize", SyncFilesTool().execute(project, buildJsonObject {}))

        val result = FindDefinitionTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolFailed("A handle into a deleted file must expire", result)
        assertTrue(toolText(result).contains("SYMBOL_ID_EXPIRED"))
    }

    fun testTypeHierarchyAcceptsIdAndReturnsResolvableIds() = runBlocking {
        registerSourceRoot("hierarchy-symbol-src")
        val baseSource = """
            package hierarchyid;
            public class BaseType {}
        """.trimIndent()
        writeProjectFile("hierarchy-symbol-src/hierarchyid/BaseType.java", baseSource)
        writeProjectFile(
            "hierarchy-symbol-src/hierarchyid/DerivedType.java",
            """
            package hierarchyid;
            public class DerivedType extends BaseType {}
            """.trimIndent()
        )
        val base = definitionAt("hierarchy-symbol-src/hierarchyid/BaseType.java", baseSource, "BaseType")

        val hierarchyResult = TypeHierarchyTool().execute(project, buildJsonObject {
            put("symbolId", base.symbolId)
        })
        assertToolSucceeded("type_hierarchy should accept symbolId", hierarchyResult)
        val hierarchy = decode<TypeHierarchyResult>(hierarchyResult)
        assertEquals(base.symbolId, hierarchy.element.symbolId)
        val derived = hierarchy.subtypes.single { it.name.endsWith("DerivedType") }
        assertNotNull("Hierarchy nodes backed by PSI must expose IDs", derived.symbolId)

        val derivedDefinition = FindDefinitionTool().execute(project, buildJsonObject {
            put("symbolId", derived.symbolId!!)
        })
        assertToolSucceeded("A hierarchy node ID should be reusable", derivedDefinition)
        val resolved = decode<DefinitionResult>(derivedDefinition)
        assertEquals(derived.symbolId, resolved.symbolId)
        assertEquals("DerivedType", resolved.symbolName)
    }

    fun testMcpServerStopInvalidatesCurrentSessionIds() = runBlocking {
        val source = "class RestartTarget { void target() {} }"
        writeProjectFile("restart-symbol-src/RestartTarget.java", source)
        val definition = definitionAt("restart-symbol-src/RestartTarget.java", source, "target")

        McpServerService.getInstance().stopServer()

        val result = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", definition.symbolId) })
        assertToolFailed("MCP server restart boundary must expire old IDs", result)
        assertTrue(toolText(result).contains("SYMBOL_ID_EXPIRED"))
    }

    fun testMcpServerStopInvalidatesOrdinaryPaginationCursors() = runBlocking {
        registerSourceRoot("restart-cursor-src")
        writeProjectFile(
            "restart-cursor-src/RestartCursor.java",
            """
            class RestartCursor {
                void uniqueRestartCursor(int value) {}
                void uniqueRestartCursor(long value) {}
            }
            """.trimIndent()
        )
        val searchResult = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "uniqueRestartCursor")
            put("pageSize", 1)
        })
        assertToolSucceeded("Search should produce a continuation", searchResult)
        val cursor = requireNotNull(decode<FindSymbolResult>(searchResult).nextCursor)

        McpServerService.getInstance().stopServer()

        val continued = FindSymbolTool().execute(project, buildJsonObject { put("cursor", cursor) })
        assertToolFailed("A pagination cursor must not survive the MCP session boundary", continued)
        assertTrue(toolText(continued).contains("Cursor not found"))
    }

    private suspend fun definitionAt(file: String, source: String, marker: String): DefinitionResult {
        val arguments = positionArguments(file, source, marker)
        val result = FindDefinitionTool().execute(project, arguments)
        assertToolSucceeded("find_definition at $file / $marker", result)
        return decode(result)
    }

    private fun positionArguments(file: String, source: String, marker: String): JsonObject {
        val offset = source.indexOf(marker)
        require(offset >= 0) { "Marker '$marker' is absent from fixture" }
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val line = source.substring(0, offset).count { it == '\n' } + 1
        val column = offset - lineStart + 1
        return buildJsonObject {
            put("file", file)
            put("line", line)
            put("column", column)
        }
    }

    private inline fun <reified T> decode(result: CallToolResult): T =
        json.decodeFromString(toolText(result))
}
