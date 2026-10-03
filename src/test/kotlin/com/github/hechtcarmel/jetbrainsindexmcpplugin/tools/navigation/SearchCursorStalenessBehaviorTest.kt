package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SearchCursorStalenessBehaviorTest : McpPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("stale-search-src")
        writeProjectFile("stale-search-src/ReviewPaging.java", """
            package reviewpaging;
            interface ReviewPagingBase { void reviewPagingMethod(); }
            class ReviewPagingOne implements ReviewPagingBase { public void reviewPagingMethod() {} }
            class ReviewPagingTwo implements ReviewPagingBase { public void reviewPagingMethod() {} }
            class ReviewPagingUses {
                void run(ReviewPagingBase base) { base.reviewPagingMethod(); base.reviewPagingMethod(); }
            }
        """.trimIndent())
        writeProjectFile("stale-search-src/Unrelated.java", "class Unrelated {}")
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testClassCursorReturnsStalePageAfterAnUnrelatedPsiEdit() = runBlocking {
        assertCursorSurvivesEdit(FindClassTool(), "classes", buildJsonObject { put("query", "ReviewPaging") })
    }

    fun testSymbolCursorReturnsStalePageAfterAnUnrelatedPsiEdit() = runBlocking {
        assertCursorSurvivesEdit(FindSymbolTool(), "symbols", buildJsonObject { put("query", "reviewPagingMethod") })
    }

    fun testLastCachedSymbolPageSurvivesAnUnrelatedPsiEdit() = runBlocking {
        writeProjectFile("stale-search-src/Overloads.java", """
            class Overloads {
                void lastCachedOverload() {}
                void lastCachedOverload(int value) {}
            }
        """.trimIndent())
        assertCursorSurvivesEdit(FindSymbolTool(), "symbols", buildJsonObject {
            put("query", "lastCachedOverload")
            put("matchMode", "exact")
        })
    }

    fun testImplementationsCursorReturnsStalePageAfterAnUnrelatedPsiEdit() = runBlocking {
        assertCursorSurvivesEdit(FindImplementationsTool(), "implementations", buildJsonObject {
            put("language", "Java")
            put("symbol", "reviewpaging.ReviewPagingBase")
        })
    }

    fun testReferencesCursorReturnsStalePageAfterAnUnrelatedPsiEdit() = runBlocking {
        assertCursorSurvivesEdit(FindUsagesTool(), "usages", buildJsonObject {
            put("language", "Java")
            put("symbol", "reviewpaging.ReviewPagingBase")
        })
    }

    fun testDeletedCachedDeclarationDoesNotRebindToANearbyClass() = runBlocking {
        val tool = FindClassTool()
        val first = tool.execute(project, buildJsonObject { put("query", "ReviewPaging"); put("pageSize", 1) })
        assertToolSucceeded("Create a cached class search", first)
        val cursor = Json.parseToJsonElement(toolText(first)).jsonObject.getValue("nextCursor").jsonPrimitive.content
        val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/stale-search-src/ReviewPaging.java"))
        WriteCommandAction.runWriteCommandAction(project) {
            val psi = PsiManager.getInstance(project).findFile(file) as PsiJavaFile
            psi.classes.forEach { it.delete() }
        }
        val next = tool.execute(project, buildJsonObject { put("cursor", cursor) })
        assertToolFailed("An unresolvable exact pointer must never select a nearby declaration", next)
        assertTrue(toolText(next).contains("re-search"))
    }

    private suspend fun assertCursorSurvivesEdit(tool: McpTool, itemsField: String, arguments: JsonObject) {
        val firstResult = tool.execute(project, buildJsonObject {
            arguments.forEach { (key, value) -> put(key, value) }
            put("pageSize", 1)
        })
        assertToolSucceeded("${tool.name} must produce a real first page", firstResult)
        val first = Json.parseToJsonElement(toolText(firstResult)).jsonObject
        val cursor = first.getValue("nextCursor").jsonPrimitive.content
        assertEquals(1, first.getValue(itemsField).jsonArray.size)
        assertTrue("The fixture must require pagination", first.getValue("hasMore").jsonPrimitive.boolean)

        val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/stale-search-src/Unrelated.java"))
        WriteCommandAction.runWriteCommandAction(project) {
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
            document.insertString(0, "// Unrelated edit\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val nextResult = tool.execute(project, buildJsonObject { put("cursor", cursor) })
        assertToolSucceeded("${tool.name} must preserve the legacy stale-page contract after edits", nextResult)
        val next = Json.parseToJsonElement(toolText(nextResult)).jsonObject
        assertTrue("The old search snapshot must be explicitly marked stale", next.getValue("stale").jsonPrimitive.boolean)
        assertEquals(1, next.getValue(itemsField).jsonArray.size)
        val symbol = if (itemsField == "usages") next.getValue("resolvedSymbol").jsonObject
            else next.getValue(itemsField).jsonArray.single().jsonObject
        val symbolId = symbol.getValue("symbolId").jsonPrimitive.content
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", symbolId) })
        assertToolSucceeded("Surviving exact pointers must still provide usable handles", info)
    }
}
