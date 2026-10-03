package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.replaceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

class HierarchyCompatibilityBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        registerSourceRoot("compat-src")
    }

    override fun tearDown() {
        try {
            HierarchyContinuationRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testLegacyCallHierarchyKeepsNestedChildrenAndPerNodeLimits() = runBlocking {
        writeProjectFile("compat-src/Calls.java", buildString {
            append("package compat; class Calls { void root() { left(); right(); }\n")
            for (branch in listOf("left", "right")) {
                append("void $branch() {")
                repeat(25) { append("${branch}Leaf$it();") }
                append("}\n")
                repeat(25) { append("void ${branch}Leaf$it() {}\n") }
            }
            append("}")
        })
        val result = CallHierarchyTool().execute(project, callArguments())
        assertToolSucceeded("Legacy call hierarchy", result)
        val tree = json.decodeFromString<CallHierarchyResult>(toolText(result))
        assertEquals(2, tree.calls.size)
        tree.calls.forEach { branch -> assertEquals("Each branch retains its own 20-result limit", 20, branch.children.orEmpty().size) }
        fun count(nodes: List<CallElement>): Int = nodes.sumOf { 1 + count(it.children.orEmpty()) }
        assertEquals("Legacy clients must receive more than 20 nodes across levels", 42, count(tree.calls))
        assertNull(tree.cursor)
    }

    fun testLegacyTypeHierarchyKeepsSupertypeChainsAndSeparateSubtypeLimit() = runBlocking {
        writeProjectFile("compat-src/Types.java", buildString {
            append("package compat; class Grand {} class Parent extends Grand {} class Root extends Parent {}\n")
            repeat(105) { append("class Child$it extends Root {}\n") }
        })
        val result = TypeHierarchyTool().execute(project, typeArguments())
        assertToolSucceeded("Legacy type hierarchy", result)
        val tree = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        assertEquals(listOf("compat.Parent"), tree.supertypes.map { it.name })
        assertEquals(listOf("compat.Grand"), tree.supertypes.single().supertypes.orEmpty().map { it.name })
        assertEquals("Supertypes must not spend the legacy 100-subtype limit", 100, tree.subtypes.size)
        assertNull(tree.cursor)
    }

    fun testCallPagesRetainParentAndDepthAcrossCursors() = runBlocking {
        smallCallFixture()
        assertPageRelations(CallHierarchyTool(), callArguments(), "calls")
    }

    fun testLegacyTypeHierarchyDoesNotCapDirectSupertypes() = runBlocking {
        writeProjectFile("compat-src/Types.java", buildString {
            append("package compat;\n")
            repeat(105) { append("interface Parent$it {}\n") }
            append("class Root implements ")
            append((0 until 105).joinToString(", ") { "Parent$it" })
            append(" {}")
        })
        val result = TypeHierarchyTool().execute(project, typeArguments())
        assertToolSucceeded("Legacy wide supertype hierarchy", result)
        val tree = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        assertEquals((0 until 105).map { "compat.Parent$it" }.toSet(), tree.supertypes.map { it.name }.toSet())
    }

    fun testTypePagesRetainParentAndDepthAcrossCursors() = runBlocking {
        smallTypeFixture()
        assertPageRelations(TypeHierarchyTool(), typeArguments(), "subtypes")
    }

    private suspend fun assertPageRelations(tool: McpTool, arguments: JsonObject, field: String) {
        val nodes = mutableListOf<JsonObject>()
        var cursor: String? = null
        var rootId: String? = null
        var remaining = 20
        do {
            assertTrue("Traversal must terminate", remaining-- > 0)
            val result = tool.execute(project, buildJsonObject {
                if (cursor == null) arguments.forEach { (key, value) -> put(key, value) } else put("cursor", cursor)
                put("maxNodes", 1)
            })
            assertToolSucceeded("A hierarchy page", result)
            val page = json.parseToJsonElement(toolText(result)).jsonObject
            val root = page.getValue("element").jsonObject
            val currentRootId = root.getValue("nodeId").jsonPrimitive.content
            if (rootId == null) rootId = currentRootId else assertEquals(rootId, currentRootId)
            assertEquals(0, root.getValue("depth").jsonPrimitive.int)
            nodes += page.getValue(field).jsonArray.map { it.jsonObject }
            cursor = page["cursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        assertEquals(4, nodes.size)
        val byId = nodes.associateBy { it.getValue("nodeId").jsonPrimitive.content }
        assertEquals(4, byId.size)
        val firstLevel = nodes.filter { it.getValue("depth").jsonPrimitive.int == 1 }
        assertEquals(2, firstLevel.size)
        firstLevel.forEach { assertEquals(rootId, it.getValue("parentId").jsonPrimitive.content) }
        nodes.filter { it.getValue("depth").jsonPrimitive.int == 2 }.forEach { leaf ->
            val parent = byId.getValue(leaf.getValue("parentId").jsonPrimitive.content)
            assertEquals(1, parent.getValue("depth").jsonPrimitive.int)
            val parentName = parent.getValue("name").jsonPrimitive.content.substringAfterLast('.').substringBefore('(')
            assertTrue("A leaf must reference its own branch", leaf.getValue("name").jsonPrimitive.content.contains(parentName, ignoreCase = true))
        }
        assertEquals(2, nodes.count { it.getValue("depth").jsonPrimitive.int == 2 })
    }

    fun testCallPageSurvivesContinuationBudgetExhaustion() = runBlocking {
        smallCallFixture()
        assertPageSurvivesBudget(CallHierarchyTool(), callArguments(), "calls")
    }

    fun testTypePageSurvivesContinuationBudgetExhaustion() = runBlocking {
        smallTypeFixture()
        assertPageSurvivesBudget(TypeHierarchyTool(), typeArguments(), "subtypes")
    }

    private suspend fun assertPageSurvivesBudget(tool: McpTool, arguments: JsonObject, field: String) {
        val registry = HierarchyContinuationRegistry(maxTotalPointers = 3)
        ApplicationManager.getApplication().replaceService(HierarchyContinuationRegistry::class.java,
            registry, testRootDisposable)
        val result = tool.execute(project, JsonObject(arguments + ("maxNodes" to JsonPrimitive(1))))
        assertToolSucceeded("A full continuation cache must not discard the computed page", result)
        val page = json.parseToJsonElement(toolText(result)).jsonObject
        assertEquals(1, page.getValue(field).jsonArray.size)
        assertTrue(page.getValue("hasMore").jsonPrimitive.boolean)
        assertTrue(page.getValue("truncated").jsonPrimitive.boolean)
        assertNull(page["cursor"]?.jsonPrimitive?.contentOrNull)
        assertTrue("Explain why progress cannot continue", page.getValue("truncationReason").jsonPrimitive.content.contains("Narrow"))
        assertEquals("No oversized frontier may be retained", 0, registry.sizeForTest())
    }

    private fun smallCallFixture() {
        writeProjectFile("compat-src/Calls.java", """
            package compat;
            class Calls {
                void root() { left(); right(); }
                void left() { leftLeaf(); }
                void right() { rightLeaf(); }
                void leftLeaf() {}
                void rightLeaf() {}
            }
        """.trimIndent())
    }

    private fun smallTypeFixture() {
        writeProjectFile("compat-src/Types.java", """
            package compat;
            class Root {}
            class Left extends Root {}
            class Right extends Root {}
            class LeftLeaf extends Left {}
            class RightLeaf extends Right {}
        """.trimIndent())
    }

    private fun callArguments() = buildJsonObject {
        put("language", "Java")
        put("symbol", "compat.Calls#root()")
        put("direction", "callees")
        put("depth", 3)
    }

    private fun typeArguments() = buildJsonObject { put("className", "compat.Root") }
}
