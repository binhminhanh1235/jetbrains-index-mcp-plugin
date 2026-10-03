package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Exercises exact Kotlin getter/setter targets through the Java PSI bridge. */
class KotlinCallHierarchyAccessorBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        registerSourceRoot("accessor-probe-src")
        writeProjectFile("accessor-probe-src/Bean.kt", """
            package accessorprobe
            class Bean {
                var value: Int
                    get() = load()
                    set(v) { save(v) }
                private fun load() = 0
                private fun save(v: Int) {}
            }
        """.trimIndent())
        writeProjectFile("accessor-probe-src/Use.java", """
            package accessorprobe;
            class Use { void root(Bean bean) { bean.getValue(); bean.setValue(1); } }
        """.trimIndent())
    }

    override fun tearDown() {
        try {
            HierarchyContinuationRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testDepthTwoKeepsAccessorExpansionParents() = runBlocking {
        val calls = mutableListOf<CallElement>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = CallHierarchyTool().execute(project, buildJsonObject {
                val currentCursor = cursor
                if (currentCursor == null) {
                    put("language", "Java")
                    put("symbol", "accessorprobe.Use#root(accessorprobe.Bean)")
                    put("direction", "callees")
                    put("depth", 2)
                } else put("cursor", currentCursor)
                put("maxNodes", 1)
            })
            assertToolSucceeded("Expand both Kotlin accessors", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            calls += page.calls
            cursor = page.cursor
            assertTrue("Probe traversal must terminate", ++pages <= 12)
        } while (cursor != null)
        val getter = calls.single { it.name == "Bean.getValue()" }
        val setter = calls.single { it.name == "Bean.setValue(int)" }
        val load = calls.single { it.name == "Bean.load()" }
        val save = calls.single { it.name == "Bean.save(int)" }
        assertEquals(2, load.depth)
        assertEquals(2, save.depth)
        assertEquals("Getter body invokes load", getter.nodeId, load.parentId)
        assertEquals("Setter body invokes save", setter.nodeId, save.parentId)
    }

    fun testSetterHandleRoundTripsToTheSetterCallable() = runBlocking {
        val first = CallHierarchyTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "accessorprobe.Use#root(accessorprobe.Bean)")
            put("direction", "callees")
            put("depth", 1)
            put("maxNodes", 10)
        })
        assertToolSucceeded("Discover setter handle", first)
        val setter = json.decodeFromString<CallHierarchyResult>(toolText(first)).calls.single {
            it.name == "Bean.setValue(int)"
        }
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", setter.symbolId) })
        assertToolSucceeded("Read setter metadata before following its calls", info)
        assertEquals("setValue", json.decodeFromString<SymbolInfoResult>(toolText(info)).name)
        val followed = CallHierarchyTool().execute(project, buildJsonObject {
            put("symbolId", requireNotNull(setter.symbolId))
            put("direction", "callees")
            put("depth", 1)
            put("maxNodes", 10)
        })
        assertToolSucceeded("Reuse the exact setter callable handle", followed)
        val hierarchy = json.decodeFromString<CallHierarchyResult>(toolText(followed))
        assertEquals("The published setter handle must not retarget to the getter", setter.name, hierarchy.element.name)
    }

    fun testQueryingAPropertyKeepsItsHandleBoundToTheProperty() = runBlocking {
        for (paged in listOf(false, true)) {
            val discovery = FindDefinitionTool().execute(project, buildJsonObject {
                put("file", "accessor-probe-src/Bean.kt")
                put("line", 3)
                put("column", 9)
            })
            assertToolSucceeded("Discover the source property", discovery)
            val property = json.decodeFromString<DefinitionResult>(toolText(discovery))
            assertEquals("value", property.symbolName)
            val original = ReadAction.compute<PsiElement, Throwable> {
                SymbolIdRegistry.getInstance().resolve(project, requireNotNull(property.symbolId)).getOrThrow()
            }
            val query = CallHierarchyTool().execute(project, buildJsonObject {
                put("symbolId", property.symbolId)
                put("direction", "callees")
                if (paged) put("maxNodes", 1)
            })
            assertToolSucceeded("A property query can select its getter", query)
            val lookup = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", property.symbolId) })
            assertToolSucceeded("The original property handle remains usable", lookup)
            assertEquals("value", json.decodeFromString<SymbolInfoResult>(toolText(lookup)).name)
            ReadAction.run<Throwable> {
                assertSame(original, SymbolIdRegistry.getInstance().resolve(project, requireNotNull(property.symbolId)).getOrThrow())
            }
        }
    }
}
