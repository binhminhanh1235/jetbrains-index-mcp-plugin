package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinCallHierarchyIdentityBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("accessor-src")
        writeProjectFile("accessor-src/Bean.kt", """
            package accessors
            class Bean {
                var value: Int
                    get() = load()
                    set(v) { save(v) }
                private fun load() = 0
                private fun save(v: Int) {}
            }
        """.trimIndent())
        writeProjectFile("accessor-src/Use.java", """
            package accessors;
            class Use { void root(Bean bean) { bean.getValue(); bean.setValue(1); } }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testLegacyCalleesKeepBothKotlinPropertyAccessors() = runBlocking {
        val result = CallHierarchyTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "accessors.Use#root(accessors.Bean)")
            put("direction", "callees")
            put("depth", 1)
        })
        assertToolSucceeded("Discover getter and setter from Java", result)
        val hierarchy = Json { ignoreUnknownKeys = true }.decodeFromString<CallHierarchyResult>(toolText(result))
        assertEquals(setOf("Bean.getValue()", "Bean.setValue(int)"), hierarchy.calls.map { it.name }.toSet())
    }

    fun testPagedCalleesKeepBothKotlinPropertyAccessors() = runBlocking {
        val calls = mutableListOf<CallElement>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = CallHierarchyTool().execute(project, buildJsonObject {
                val continuation = cursor
                if (continuation == null) {
                    put("language", "Java")
                    put("symbol", "accessors.Use#root(accessors.Bean)")
                    put("direction", "callees")
                    put("depth", 1)
                    put("maxNodes", 1)
                } else {
                    put("cursor", continuation)
                }
            })
            assertToolSucceeded("Page through Kotlin accessors", result)
            val page = Json { ignoreUnknownKeys = true }.decodeFromString<CallHierarchyResult>(toolText(result))
            calls.addAll(page.calls)
            cursor = page.cursor
            assertTrue("Traversal must terminate", ++pages <= 4)
        } while (cursor != null)
        assertEquals(setOf("Bean.getValue()", "Bean.setValue(int)"), calls.map { it.name }.toSet())
        assertEquals("Distinct accessors need distinct traversal identities", 2, calls.map { it.nodeId }.toSet().size)
    }
}
