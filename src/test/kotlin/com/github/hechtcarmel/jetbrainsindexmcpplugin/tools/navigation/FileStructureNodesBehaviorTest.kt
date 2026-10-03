package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileStructureNodesBehaviorTest : McpPlatformTestCase() {

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

    fun testFileStructureKeepsLegacyTextAndReturnsExactStructuredHandles() = runBlocking {
        writeProjectFile(
            "structure-src/OverloadedStructure.java",
            """
            public class OverloadedStructure {
                public void choose(int value) {} public void choose(long value) {}
            }
            """.trimIndent()
        )

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/OverloadedStructure.java")
            put("includeSymbolIds", true)
        })
        assertToolSucceeded("file_structure should return Java structure", result)

        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        assertTrue("Legacy formatted output must remain available", payload.structure.contains("OverloadedStructure"))

        val classNode = payload.nodes.single()
        assertEquals("OverloadedStructure", classNode.name)
        assertEquals("CLASS", classNode.kind.name)
        assertEquals(listOf("public"), classNode.modifiers)
        assertEquals(1, classNode.line)
        assertEquals(3, classNode.endLine)
        assertNotNull("The class node should expose a symbol handle", classNode.symbolId)

        val overloads = classNode.children.filter { it.name == "choose" }
        assertEquals("Both same-line overloads must be represented", 2, overloads.size)
        assertEquals("Both overloads intentionally share a source line", 1, overloads.map { it.line }.toSet().size)
        assertEquals(listOf("public"), overloads.first().modifiers)
        assertTrue(overloads.all { !it.signature.isNullOrBlank() })

        val ids = overloads.map { requireNotNull(it.symbolId) }
        assertEquals("Exact PSI declarations need distinct handles", 2, ids.toSet().size)

        val parameterTypes = ReadAction.compute<Set<String>, RuntimeException> {
            ids.map { symbolId ->
                val method = SymbolIdRegistry.getInstance().resolve(project, symbolId).getOrThrow() as PsiMethod
                method.parameterList.parameters.single().type.canonicalText
            }.toSet()
        }
        assertEquals(setOf("int", "long"), parameterTypes)
        assertFalse(payload.symbolIdsTruncated)
        assertEquals(0, payload.symbolIdsOmitted)
    }

    fun testLargeOutlinePreservesEveryNodeWithoutReturningAlreadyEvictedHandles() = runBlocking {
        val fieldCount = SymbolIdRegistry.DEFAULT_MAX_ENTRIES
        writeProjectFile("structure-src/LargeStructure.java", buildString {
            appendLine("class LargeStructure {")
            repeat(fieldCount) { appendLine("  int field$it;") }
            appendLine("}")
        })

        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/LargeStructure.java")
            put("includeNodes", true)
            put("includeSymbolIds", true)
        })
        assertToolSucceeded("large outlines should retain the complete structure", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        val root = payload.nodes.single()
        assertEquals(fieldCount, root.children.size)
        assertEquals("field${fieldCount - 1}", root.children.last().name)
        assertTrue(payload.structure.contains("field${fieldCount - 1}"))
        val allNodes = listOf(root) + root.children
        val registry = SymbolIdRegistry.getInstance()
        val handles = allNodes.mapNotNull { it.symbolId }
        assertEquals(100, handles.size)
        assertTrue(payload.symbolIdsTruncated)
        assertEquals(allNodes.size - handles.size, payload.symbolIdsOmitted)
        assertNotNull("preorder gives the root a usable handle", root.symbolId)
        ReadAction.run<RuntimeException> {
            for (handle in handles) {
                assertTrue("every returned handle must still resolve", registry.resolve(project, handle).isSuccess)
            }
        }
    }

    fun testLegacyResponseOmitsStructuredNodesAndDoesNotAllocateHandles() = runBlocking {
        writeProjectFile("structure-src/Legacy.java", "class Legacy { int value; }")
        val registry = SymbolIdRegistry.getInstance()
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/Legacy.java")
        })
        assertToolSucceeded("legacy file structure", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        assertTrue(payload.structure.contains("Legacy"))
        assertTrue(payload.nodes.isEmpty())
        assertEquals(0, registry.sizeForTest())
    }

    fun testStructuredNodesWithoutHandlesDoNotTouchRegistry() = runBlocking {
        writeProjectFile("structure-src/NodesOnly.java", "class NodesOnly { int value; }")
        val registry = SymbolIdRegistry.getInstance()
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/NodesOnly.java")
            put("includeNodes", true)
        })
        assertToolSucceeded("nodes-only file structure", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        assertTrue(payload.nodes.isNotEmpty())
        assertTrue(payload.nodes.flatMap { listOf(it) + it.children }.all { it.symbolId == null })
        assertEquals(0, registry.sizeForTest())
    }

    fun testHandleBudgetIsConfigurableAndReportsOmissions() = runBlocking {
        writeProjectFile("structure-src/Budget.java", "class Budget { int first; int second; }")
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/Budget.java")
            put("includeSymbolIds", true)
            put("maxSymbolIds", 1)
        })
        assertToolSucceeded("bounded file structure", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        val nodes = listOf(payload.nodes.single()) + payload.nodes.single().children
        assertEquals(1, nodes.count { it.symbolId != null })
        assertTrue(payload.symbolIdsTruncated)
        assertEquals(nodes.size - 1, payload.symbolIdsOmitted)
    }

    fun testInvalidHandleBudgetIsRejected() = runBlocking {
        writeProjectFile("structure-src/InvalidBudget.java", "class InvalidBudget")
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", "structure-src/InvalidBudget.java")
            put("includeSymbolIds", true)
            put("maxSymbolIds", 101)
        })
        assertToolFailed("invalid handle budget", result)
        assertTrue(toolText(result).contains("maxSymbolIds must be between 1 and 100"))
    }
}
