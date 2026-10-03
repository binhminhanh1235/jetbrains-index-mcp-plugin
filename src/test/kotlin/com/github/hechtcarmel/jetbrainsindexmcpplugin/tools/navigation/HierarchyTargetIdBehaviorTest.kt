package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A navigation query may select an enclosing declaration, but must not retarget its input ID. */
class HierarchyTargetIdBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        SymbolIdRegistry.getInstance().resetSession()
        registerSourceRoot("hierarchy-target-id-src")
    }

    override fun tearDown() {
        try {
            HierarchyContinuationRegistry.getInstance().resetSession()
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testLegacyCallHierarchyPreservesParameterHandle() = runBlocking {
        verifyOriginalHandle(callHierarchy = true, paged = false)
    }

    fun testPagedCallHierarchyPreservesParameterHandle() = runBlocking {
        verifyOriginalHandle(callHierarchy = true, paged = true)
    }

    fun testLegacyTypeHierarchyPreservesMethodHandle() = runBlocking {
        verifyOriginalHandle(callHierarchy = false, paged = false)
    }

    fun testPagedTypeHierarchyPreservesMethodHandle() = runBlocking {
        verifyOriginalHandle(callHierarchy = false, paged = true)
    }

    private suspend fun verifyOriginalHandle(callHierarchy: Boolean, paged: Boolean) {
        val className = "HierarchyTarget${if (callHierarchy) "Call" else "Type"}${if (paged) "Paged" else "Legacy"}"
        val file = "hierarchy-target-id-src/$className.java"
        val source = "class $className { void work(int count) {} }"
        val originalName = if (callHierarchy) "count" else "work"
        writeProjectFile(file, source)

        val discovery = FindDefinitionTool().execute(project, buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", source.indexOf(originalName) + 1)
        })
        assertToolSucceeded("Discover the exact original declaration", discovery)
        val original = json.decodeFromString<DefinitionResult>(toolText(discovery))
        assertEquals("Fixture discovery must select the expected declaration", originalName, original.symbolName)

        val arguments = buildJsonObject {
            put("symbolId", original.symbolId)
            if (callHierarchy) put("direction", "callers")
            if (paged) put("maxNodes", 1)
        }
        val hierarchy = if (callHierarchy) {
            CallHierarchyTool().execute(project, arguments)
        } else {
            TypeHierarchyTool().execute(project, arguments)
        }
        assertToolSucceeded("The hierarchy query may resolve an enclosing declaration", hierarchy)

        val lookup = SymbolInfoTool().execute(project, buildJsonObject {
            put("symbolId", original.symbolId)
        })
        assertToolSucceeded("The original handle must remain usable after a read-only hierarchy query", lookup)
        val after = json.decodeFromString<SymbolInfoResult>(toolText(lookup))
        assertEquals("A hierarchy query must not rebind the input handle to an enclosing declaration", originalName, after.name)
        assertEquals(original.symbolId, after.symbolId)
    }
}
