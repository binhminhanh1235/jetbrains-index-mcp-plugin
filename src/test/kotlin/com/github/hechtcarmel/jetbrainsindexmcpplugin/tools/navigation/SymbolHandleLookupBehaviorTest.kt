package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.RenameSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project.SyncFilesTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put

class SymbolHandleLookupBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    private val file = "handles-src/handles/Service.java"
    private val source = "package handles;\nclass Service {\n  void work() {}\n  void other() {}\n}"

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        SymbolIdRegistry.getInstance().resetSession()
        registerSourceRoot("handles-src")
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testDefinitionHandleTracksLineShiftAndRenameAndIsAcceptedByBothTools() = runBlocking {
        writeProjectFile(file, source)
        val discovered = FindDefinitionTool().execute(project, position(source, "work"))
        assertToolSucceeded("Discover declaration", discovered)
        val handle = json.decodeFromString<DefinitionResult>(toolText(discovered)).symbolId
        val shifted = "// external edit\n\n$source"
        writeProjectFile(file, shifted)
        syncFile()
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Resolve after line shift", info)
        val metadata = json.decodeFromString<SymbolInfoResult>(toolText(info))
        assertEquals("work", metadata.name)
        assertEquals(5, metadata.line)
        assertEquals(handle, metadata.symbolId)

        val renamed = RenameSymbolTool().execute(project, buildJsonObject {
            position(shifted, "work").forEach { (key, value) -> put(key, value) }
            put("newName", "compute")
        })
        assertToolSucceeded("Legacy rename", renamed)
        val resolved = FindDefinitionTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Resolve exact handle after rename", resolved)
        val definition = json.decodeFromString<DefinitionResult>(toolText(resolved))
        assertEquals("compute", definition.symbolName)
        assertEquals(handle, definition.symbolId)
    }

    fun testDeletedDeclarationExpiresInsteadOfSelectingItsNeighbor() = runBlocking {
        writeProjectFile(file, source)
        val result = FindDefinitionTool().execute(project, position(source, "work"))
        assertToolSucceeded("Discover declaration", result)
        val handle = json.decodeFromString<DefinitionResult>(toolText(result)).symbolId
        writeProjectFile(file, source.replace("  void work() {}\n", ""))
        syncFile()
        val expired = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertEquals(true, expired.isError)
        assertTrue(toolText(expired).contains("SYMBOL_ID_EXPIRED"))
    }

    private fun position(text: String, marker: String): JsonObject {
        val offset = text.indexOf(marker)
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        return buildJsonObject {
            put("file", file)
            put("line", text.take(offset).count { it == '\n' } + 1)
            put("column", offset - lineStart + 1)
        }
    }

    private suspend fun syncFile() {
        val result = SyncFilesTool().execute(project, buildJsonObject {
            put("paths", buildJsonArray { add(file) })
        })
        assertToolSucceeded("Synchronize external source edit", result)
    }
}
