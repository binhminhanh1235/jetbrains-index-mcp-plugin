package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.CallHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeElementData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.applyHierarchyPage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

class HierarchyPaginationBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        SymbolIdRegistry.getInstance().resetSession()
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

    fun testTypeHierarchyPagesUseDeterministicBreadthFirstTraversalWithoutLoss() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/pages/Types.java",
            """
                package pages;

                class Root {}
                class Alpha extends Root {}
                class Beta extends Root {}
                class AlphaLeaf extends Alpha {}
                class BetaLeaf extends Beta {}
            """.trimIndent()
        )

        val pages = mutableListOf<TypeHierarchyResult>()
        val tool = TypeHierarchyTool()
        var cursor: String? = null
        var remainingPages = 20
        do {
            assertTrue("type hierarchy cursor chain must terminate", remainingPages-- > 0)
            val result = tool.execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) put("className", "pages.Root") else put("cursor", cursor!!)
            })
            assertToolSucceeded("type hierarchy page should succeed", result)
            val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
            assertTrue("maxNodes must be enforced while traversing", page.returnedNodes <= 1)
            assertEquals(page.returnedNodes, page.supertypes.size + page.subtypes.size)
            assertEquals(page.hasMore, page.cursor != null)
            assertEquals(page.hasMore, page.truncated)
            pages += page
            cursor = page.cursor
        } while (cursor != null)

        val names = pages.flatMap { page -> page.traversal }.map { it.element.name }
        assertEquals(
            "the order-preserving view must contain exactly the legacy nodes",
            pages.sumOf { it.supertypes.size + it.subtypes.size },
            pages.sumOf { it.traversal.size }
        )
        assertEquals(
            "Each BFS level must be stable before its children",
            listOf("pages.Alpha", "pages.Beta", "pages.AlphaLeaf", "pages.BetaLeaf"),
            names
        )
        assertEquals("No node may be duplicated across pages", names.size, names.toSet().size)
        assertTrue("Every non-empty hierarchy must fit in a finite number of pages", pages.size <= 10)
    }

    fun testCallHierarchyPagesUseDeterministicBreadthFirstTraversalWithoutLoss() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val source = """
            package pages;

            class Calls {
                void leftLeaf() {}
                void rightLeaf() {}
                void left() { leftLeaf(); }
                void right() { rightLeaf(); }
                void root() { left(); right(); }
            }
        """.trimIndent()
        writeProjectFile("src/pages/Calls.java", source)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val pages = mutableListOf<CallHierarchyResult>()
        val tool = CallHierarchyTool()
        var cursor: String? = null
        var remainingPages = 20
        do {
            assertTrue("call hierarchy cursor chain must terminate", remainingPages-- > 0)
            val result = tool.execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) {
                    put("file", "src/pages/Calls.java")
                    put("line", line)
                    put("column", column)
                    put("direction", "callees")
                    put("depth", 2)
                } else {
                    put("cursor", cursor!!)
                }
            })
            assertToolSucceeded("call hierarchy page should succeed", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            assertTrue("maxNodes must be enforced while traversing", page.returnedNodes <= 1)
            assertEquals(page.returnedNodes, page.calls.size)
            assertEquals(page.hasMore, page.cursor != null)
            assertEquals(page.hasMore, page.truncated)
            pages += page
            cursor = page.cursor
        } while (cursor != null)

        val names = pages.flatMap { it.calls }.map { it.name.substringBefore('(').substringAfterLast('.') }
        assertEquals(
            "Direct callees must precede second-level callees in deterministic order",
            listOf("left", "right", "leftLeaf", "rightLeaf"),
            names
        )
        assertEquals("No call node may be duplicated across pages", names.size, names.toSet().size)
        assertTrue("Every non-empty hierarchy must fit in a finite number of pages", pages.size <= 6)
    }

    fun testTypeLeafDoesNotAdvertiseAnEmptyTerminalCursor() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/pages/TerminalType.java",
            "package pages; class TerminalRoot {} class TerminalChild extends TerminalRoot {}"
        )

        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("className", "pages.TerminalRoot")
            put("maxNodes", 1)
        })

        assertToolSucceeded("terminal type hierarchy should succeed", result)
        val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        assertEquals(listOf("pages.TerminalChild"), page.traversal.map { it.element.name })
        assertFalse("a leaf must not create a cursor whose next page is empty", page.hasMore)
        assertNull(page.cursor)
    }

    fun testCallLeafDoesNotAdvertiseAnEmptyTerminalCursor() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val source = """
            package pages;
            class TerminalCalls {
                void leaf() {}
                void root() { leaf(); }
            }
        """.trimIndent()
        writeProjectFile("src/pages/TerminalCalls.java", source)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val result = CallHierarchyTool().execute(project, buildJsonObject {
            put("file", "src/pages/TerminalCalls.java")
            put("line", line)
            put("column", column)
            put("direction", "callees")
            // Depth two schedules the leaf expansion, which used to manufacture an empty page.
            put("depth", 2)
            put("maxNodes", 1)
        })

        assertToolSucceeded("terminal call hierarchy should succeed", result)
        val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
        assertEquals(1, page.returnedNodes)
        assertTrue(page.calls.single().name.contains("TerminalCalls.leaf"))
        assertFalse("a leaf must not create a cursor whose next page is empty", page.hasMore)
        assertNull(page.cursor)
    }

    fun testCallHierarchyKeepsOverloadsDistinctAcrossPages() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val source = """
            package pages;
            class Overloads {
                void hit(int value) {}
                void hit(String value) {}
                void root() { hit(1); hit("value"); }
            }
        """.trimIndent()
        writeProjectFile("src/pages/Overloads.java", source)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val tool = CallHierarchyTool()
        val names = mutableListOf<String>()
        var cursor: String? = null
        do {
            val result = tool.execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) {
                    put("file", "src/pages/Overloads.java")
                    put("line", line)
                    put("column", column)
                    put("direction", "callees")
                    put("depth", 1)
                } else put("cursor", cursor!!)
            })
            assertToolSucceeded("overload hierarchy page should succeed", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            names += page.calls.map { it.name }
            cursor = page.cursor
        } while (cursor != null)

        assertEquals(listOf("Overloads.hit(int)", "Overloads.hit(String)"), names)
    }

    fun testTypeCursorRootUsesResolvedDeclarationInsteadOfReferenceLeaf() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile("src/pages/ReferenceRoot.java", "package pages; class ReferenceRoot {}")
        val referenceSource = "package pages; class ReferenceFirst extends ReferenceRoot {}"
        val referencePath = writeProjectFile("src/pages/ReferenceFirst.java", referenceSource)
        writeProjectFile(
            "src/pages/ReferenceSecond.java",
            "package pages; class ReferenceSecond extends ReferenceRoot {}"
        )
        val markerOffset = referenceSource.lastIndexOf("ReferenceRoot")
        val line = referenceSource.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = referenceSource.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val tool = TypeHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("file", "src/pages/ReferenceFirst.java")
            put("line", line)
            put("column", column)
            put("maxNodes", 1)
        })
        assertToolSucceeded("type hierarchy should resolve a type reference", firstResult)
        val first = json.decodeFromString<TypeHierarchyResult>(toolText(firstResult))
        assertEquals("pages.ReferenceRoot", first.element.name)
        val cursor = requireNotNull(first.cursor)

        Files.delete(referencePath)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(requireNotNull(project.basePath))
            ?.refresh(false, true)

        val secondResult = tool.execute(project, buildJsonObject {
            put("cursor", cursor)
            put("maxNodes", 1)
        })
        assertToolSucceeded(
            "deleting the reference leaf must not invalidate the resolved hierarchy root",
            secondResult
        )
        val second = json.decodeFromString<TypeHierarchyResult>(toolText(secondResult))
        assertEquals(listOf("pages.ReferenceSecond"), second.traversal.map { it.element.name })
    }

    fun testCallCursorRootUsesResolvedDeclarationInsteadOfCallSiteLeaf() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile(
            "src/pages/ReferenceTarget.java",
            "package pages; class ReferenceTarget { static void hit() {} }"
        )
        val callerSource =
            "package pages; class ReferenceCallerA { void call() { ReferenceTarget.hit(); } }"
        val callerPath = writeProjectFile("src/pages/ReferenceCallerA.java", callerSource)
        writeProjectFile(
            "src/pages/ReferenceCallerB.java",
            "package pages; class ReferenceCallerB { void call() { ReferenceTarget.hit(); } }"
        )
        val markerOffset = callerSource.indexOf("hit")
        val line = callerSource.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = callerSource.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val tool = CallHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("file", "src/pages/ReferenceCallerA.java")
            put("line", line)
            put("column", column)
            put("direction", "callers")
            put("depth", 1)
            put("maxNodes", 1)
        })
        assertToolSucceeded("call hierarchy should resolve a call-site reference", firstResult)
        val first = json.decodeFromString<CallHierarchyResult>(toolText(firstResult))
        assertTrue(first.element.name.contains("ReferenceTarget.hit"))
        val cursor = requireNotNull(first.cursor)

        Files.delete(callerPath)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(requireNotNull(project.basePath))
            ?.refresh(false, true)

        val secondResult = tool.execute(project, buildJsonObject {
            put("cursor", cursor)
            put("maxNodes", 1)
        })
        assertToolSucceeded(
            "deleting the call-site leaf must not invalidate the resolved hierarchy root",
            secondResult
        )
        val second = json.decodeFromString<CallHierarchyResult>(toolText(secondResult))
        assertTrue(second.calls.single().name.contains("ReferenceCallerB.call"))
    }

    fun testWideTypeLevelContinuesPastHistoricalHandlerCap() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val subtypeCount = 105
        writeProjectFile(
            "src/pages/WideTypes.java",
            buildString {
                appendLine("package pages;")
                appendLine("class WideRoot {}")
                repeat(subtypeCount) { index ->
                    appendLine("class WideChild%03d extends WideRoot {}".format(index))
                }
            }
        )

        suspend fun collectFreshTraversal(): List<String> {
            val names = mutableListOf<String>()
            val tool = TypeHierarchyTool()
            var cursor: String? = null
            var remainingPages = 12
            do {
                assertTrue("wide hierarchy cursor chain must terminate", remainingPages-- > 0)
                val result = tool.execute(project, buildJsonObject {
                    put("maxNodes", 40)
                    if (cursor == null) put("className", "pages.WideRoot") else put("cursor", cursor!!)
                })
                assertToolSucceeded("wide hierarchy page should succeed", result)
                val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
                assertTrue("maxNodes must bound every wide-level page", page.returnedNodes <= 40)
                assertEquals(page.returnedNodes, page.traversal.size)
                assertEquals(page.returnedNodes, page.supertypes.size + page.subtypes.size)
                assertEquals(page.hasMore, page.cursor != null)
                assertEquals(page.hasMore, page.truncated)
                names += page.traversal.map { it.element.name }
                cursor = page.cursor
            } while (cursor != null)
            return names
        }

        val firstTraversal = collectFreshTraversal()
        val secondTraversal = collectFreshTraversal()
        val expectedNames = (0 until subtypeCount).map { "pages.WideChild%03d".format(it) }.toSet()

        assertEquals(subtypeCount, firstTraversal.size)
        assertEquals("No node may be duplicated across wide-level pages", subtypeCount, firstTraversal.toSet().size)
        assertEquals("No wide-level node may be lost", expectedNames, firstTraversal.toSet())
        assertEquals(
            "unchanged fresh traversals must preserve the same bounded discovery order",
            firstTraversal,
            secondTraversal
        )
    }

    fun testWideCallLevelContinuesPastHistoricalHandlerCap() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val calleeCount = 25
        val source = buildString {
            appendLine("package pages;")
            appendLine("class WideCalls {")
            repeat(calleeCount) { index -> appendLine("  void call%02d() {}".format(index)) }
            append("  void root() {")
            repeat(calleeCount) { index -> append(" call%02d();".format(index)) }
            appendLine(" }")
            appendLine("}")
        }
        writeProjectFile("src/pages/WideCalls.java", source)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val names = mutableListOf<String>()
        val tool = CallHierarchyTool()
        var cursor: String? = null
        var remainingPages = 10
        do {
            assertTrue("wide call cursor chain must terminate", remainingPages-- > 0)
            val result = tool.execute(project, buildJsonObject {
                put("maxNodes", 7)
                if (cursor == null) {
                    put("file", "src/pages/WideCalls.java")
                    put("line", line)
                    put("column", column)
                    put("direction", "callees")
                    put("depth", 1)
                } else put("cursor", cursor!!)
            })
            assertToolSucceeded("wide call page should succeed", result)
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            names += page.calls.map { it.name.substringBefore('(').substringAfterLast('.') }
            cursor = page.cursor
        } while (cursor != null)

        assertEquals(calleeCount, names.size)
        assertEquals((0 until calleeCount).map { "call%02d".format(it) }, names)
    }

    fun testTypeHierarchyBoundsTerminalProbesAndBindsEachGrowingPrefixNodeOnce() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val childCount = 8
        writeProjectFile(
            "src/pages/ProbeTypes.java",
            buildString {
                appendLine("package pages;")
                appendLine("class ProbeRoot {}")
                repeat(childCount) { index ->
                    appendLine("class ProbeType%02d extends ProbeRoot {}".format(index))
                }
            }
        )
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val root = requireNotNull(facade.findClass("pages.ProbeRoot", scope))
        val children = (0 until childCount).map { index ->
            requireNotNull(facade.findClass("pages.ProbeType%02d".format(index), scope))
        }
        val handler = CountingTypeHierarchyHandler(root, children)
        LanguageHandlerRegistry.registerTypeHierarchyHandler(handler)

        val names = mutableListOf<String>()
        var cursor: String? = null
        var sawProbeOnlyPage = false
        var remainingPages = 24
        do {
            assertTrue("bounded type probe cursor chain must terminate", remainingPages-- > 0)
            val callsBefore = handler.invocations
            val result = TypeHierarchyTool().execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) put("className", "pages.ProbeRoot") else put("cursor", cursor!!)
            })
            val callsThisPage = handler.invocations - callsBefore

            assertToolSucceeded("bounded type hierarchy page should succeed", result)
            assertTrue(
                "maxNodes=1 permits at most one normal expansion plus one terminal look-ahead " +
                    "probe, got $callsThisPage",
                callsThisPage <= 2
            )
            val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
            assertTrue(page.returnedNodes <= 1)
            assertEquals(page.returnedNodes, page.traversal.size)
            assertEquals(page.hasMore, page.cursor != null)
            if (page.returnedNodes == 0) sawProbeOnlyPage = true
            names += page.traversal.map { it.element.name }
            cursor = page.cursor
        } while (cursor != null)

        assertTrue("a bounded terminal frontier should continue through a probe-only page", sawProbeOnlyPage)
        assertEquals((0 until childCount).map { "pages.ProbeType%02d".format(it) }, names)
        assertEquals(
            "growing-prefix re-reads must not allocate handles for already visited types",
            childCount + 1,
            SymbolIdRegistry.getInstance().sizeForTest()
        )
    }

    fun testCallHierarchyBoundsTerminalProbesAndBindsEachGrowingPrefixNodeOnce() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val calleeCount = 8
        val source = buildString {
            appendLine("package pages;")
            appendLine("class ProbeCalls {")
            repeat(calleeCount) { index -> appendLine("  void leaf%02d() {}".format(index)) }
            append("  void root() {")
            repeat(calleeCount) { index -> append(" leaf%02d();".format(index)) }
            appendLine(" }")
            appendLine("}")
        }
        writeProjectFile("src/pages/ProbeCalls.java", source)
        val fixtureClass = requireNotNull(
            JavaPsiFacade.getInstance(project)
                .findClass("pages.ProbeCalls", GlobalSearchScope.projectScope(project))
        )
        val root = fixtureClass.findMethodsByName("root", false).single()
        val callees = (0 until calleeCount).map { index ->
            fixtureClass.findMethodsByName("leaf%02d".format(index), false).single()
        }
        val handler = CountingCallHierarchyHandler(root, callees)
        LanguageHandlerRegistry.registerCallHierarchyHandler(handler)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val names = mutableListOf<String>()
        var cursor: String? = null
        var sawProbeOnlyPage = false
        var remainingPages = 24
        do {
            assertTrue("bounded call probe cursor chain must terminate", remainingPages-- > 0)
            val callsBefore = handler.invocations
            val result = CallHierarchyTool().execute(project, buildJsonObject {
                put("maxNodes", 1)
                if (cursor == null) {
                    put("file", "src/pages/ProbeCalls.java")
                    put("line", line)
                    put("column", column)
                    put("direction", "callees")
                    put("depth", 2)
                } else put("cursor", cursor!!)
            })
            val callsThisPage = handler.invocations - callsBefore

            assertToolSucceeded("bounded call hierarchy page should succeed", result)
            assertTrue(
                "maxNodes=1 permits at most one normal expansion plus one terminal look-ahead " +
                    "probe, got $callsThisPage",
                callsThisPage <= 2
            )
            val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
            assertTrue(page.returnedNodes <= 1)
            assertEquals(page.returnedNodes, page.calls.size)
            assertEquals(page.hasMore, page.cursor != null)
            if (page.returnedNodes == 0) sawProbeOnlyPage = true
            names += page.calls.map { it.name }
            cursor = page.cursor
        } while (cursor != null)

        assertTrue("a bounded terminal frontier should continue through a probe-only page", sawProbeOnlyPage)
        assertEquals((0 until calleeCount).map { "ProbeCalls.leaf%02d()".format(it) }, names)
        assertEquals(
            "growing-prefix re-reads must not allocate handles for already visited calls",
            calleeCount + 1,
            SymbolIdRegistry.getInstance().sizeForTest()
        )
    }

    fun testTypeHierarchyAllowsOnlyOneTerminalLookaheadAfterDiscoveryBudget() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val childCount = 4
        writeProjectFile(
            "src/pages/CyclicProbeTypes.java",
            buildString {
                appendLine("package pages;")
                appendLine("class CyclicProbeRoot {}")
                repeat(childCount) { index ->
                    appendLine("class CyclicProbeType%02d extends CyclicProbeRoot {}".format(index))
                }
            }
        )
        val facade = JavaPsiFacade.getInstance(project)
        val scope = GlobalSearchScope.projectScope(project)
        val root = requireNotNull(facade.findClass("pages.CyclicProbeRoot", scope))
        val children = (0 until childCount).map { index ->
            requireNotNull(facade.findClass("pages.CyclicProbeType%02d".format(index), scope))
        }
        val handler = CountingTypeHierarchyHandler(
            root,
            children,
            repeatVisitedChildrenForLeaves = true
        )
        LanguageHandlerRegistry.registerTypeHierarchyHandler(handler)

        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("className", "pages.CyclicProbeRoot")
            put("maxNodes", childCount)
        })

        assertToolSucceeded("cyclic type hierarchy page should succeed", result)
        val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
        assertEquals(childCount, page.returnedNodes)
        assertTrue("unresolved terminal probes must remain resumable", page.hasMore)
        assertEquals(
            "one initial lookup, one bounded root expansion, and one terminal look-ahead are allowed",
            3,
            handler.invocations
        )
    }

    fun testCallHierarchyAllowsOnlyOneTerminalLookaheadAfterDiscoveryBudget() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val calleeCount = 4
        val source = buildString {
            appendLine("package pages;")
            appendLine("class CyclicProbeCalls {")
            repeat(calleeCount) { index -> appendLine("  void leaf%02d() {}".format(index)) }
            append("  void root() {")
            repeat(calleeCount) { index -> append(" leaf%02d();".format(index)) }
            appendLine(" }")
            appendLine("}")
        }
        writeProjectFile("src/pages/CyclicProbeCalls.java", source)
        val fixtureClass = requireNotNull(
            JavaPsiFacade.getInstance(project)
                .findClass("pages.CyclicProbeCalls", GlobalSearchScope.projectScope(project))
        )
        val root = fixtureClass.findMethodsByName("root", false).single()
        val callees = (0 until calleeCount).map { index ->
            fixtureClass.findMethodsByName("leaf%02d".format(index), false).single()
        }
        val handler = CountingCallHierarchyHandler(
            root,
            callees,
            repeatVisitedCalleesForLeaves = true
        )
        LanguageHandlerRegistry.registerCallHierarchyHandler(handler)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val result = CallHierarchyTool().execute(project, buildJsonObject {
            put("file", "src/pages/CyclicProbeCalls.java")
            put("line", line)
            put("column", column)
            put("direction", "callees")
            put("depth", 2)
            put("maxNodes", calleeCount)
        })

        assertToolSucceeded("cyclic call hierarchy page should succeed", result)
        val page = json.decodeFromString<CallHierarchyResult>(toolText(result))
        assertEquals(calleeCount, page.returnedNodes)
        assertTrue("unresolved terminal probes must remain resumable", page.hasMore)
        assertEquals(
            "one initial lookup, one ordinary leaf expansion, and one terminal look-ahead are allowed",
            3,
            handler.invocations
        )
    }

    fun testTypeCursorRefreshesLiveDeclarationMetadataAndPreservesHandles() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val path = writeProjectFile(
            "src/pages/LiveTypes.java",
            """
                package pages;
                class LiveRoot {}
                class Alpha extends LiveRoot {}
                class Beta extends LiveRoot {}
                class Gamma extends LiveRoot {}
            """.trimIndent()
        )

        val tool = TypeHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("className", "pages.LiveRoot")
            put("maxNodes", 1)
        })
        assertToolSucceeded("first live hierarchy page should succeed", firstResult)
        val first = json.decodeFromString<TypeHierarchyResult>(toolText(firstResult))
        val rootSymbolId = requireNotNull(first.element.symbolId)
        val cursor = requireNotNull(first.cursor)

        val beta = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass("pages.Beta", GlobalSearchScope.projectScope(project))
        )
        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString()))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(virtualFile))
        WriteCommandAction.runWriteCommandAction(project) {
            beta.setName("RenamedBeta")
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
            document.insertString(0, "\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        val secondResult = tool.execute(project, buildJsonObject {
            put("cursor", cursor)
            put("maxNodes", 1)
        })
        assertToolSucceeded("cursor should survive declaration edits", secondResult)
        val second = json.decodeFromString<TypeHierarchyResult>(toolText(secondResult))

        assertEquals("root handle must retain identity across a line shift", rootSymbolId, second.element.symbolId)
        assertEquals(listOf("pages.RenamedBeta"), second.traversal.map { it.element.name })
        assertNotNull("refreshed declaration should expose a live handle", second.traversal.single().element.symbolId)
    }

    fun testCallCursorRefreshesLiveDeclarationMetadataAndPreservesHandles() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val source = """
            package pages;

            class LiveCalls {
                void alpha() {}
                void beta() {}
                void root() { alpha(); beta(); }
            }
        """.trimIndent()
        val path = writeProjectFile("src/pages/LiveCalls.java", source)
        val markerOffset = source.indexOf("root()")
        val line = source.substring(0, markerOffset).count { it == '\n' } + 1
        val lineStart = source.lastIndexOf('\n', markerOffset - 1) + 1
        val column = markerOffset - lineStart + 1

        val tool = CallHierarchyTool()
        val firstResult = tool.execute(project, buildJsonObject {
            put("file", "src/pages/LiveCalls.java")
            put("line", line)
            put("column", column)
            put("direction", "callees")
            put("depth", 1)
            put("maxNodes", 1)
        })
        assertToolSucceeded("first live call page should succeed", firstResult)
        val first = json.decodeFromString<CallHierarchyResult>(toolText(firstResult))
        val rootSymbolId = requireNotNull(first.element.symbolId)
        val cursor = requireNotNull(first.cursor)

        val liveClass = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass("pages.LiveCalls", GlobalSearchScope.projectScope(project))
        )
        val beta = liveClass.findMethodsByName("beta", false).single()
        val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString()))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(virtualFile))
        WriteCommandAction.runWriteCommandAction(project) {
            beta.setName("renamedBeta")
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(document)
            document.insertString(0, "\n")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }
        val expectedLine = document.getLineNumber(beta.textOffset) + 1

        val secondResult = tool.execute(project, buildJsonObject {
            put("cursor", cursor)
            put("maxNodes", 1)
        })
        assertToolSucceeded("call cursor should survive declaration edits", secondResult)
        val second = json.decodeFromString<CallHierarchyResult>(toolText(secondResult))

        assertEquals("root handle must retain identity across a line shift", rootSymbolId, second.element.symbolId)
        assertTrue(second.calls.single().name.contains("renamedBeta"))
        assertEquals(expectedLine, second.calls.single().line)
        assertNotNull(second.calls.single().symbolId)
    }

    fun testContinuationFailsClearlyWhenRootPointerWasDeleted() = runBlocking {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        writeProjectFile("src/pages/DeletedRoot.java", "package pages; class DeletedRoot {}")
        writeProjectFile("src/pages/DeletedChild.java", "package pages; class DeletedChild extends DeletedRoot {}")
        writeProjectFile("src/pages/DeletedLeaf.java", "package pages; class DeletedLeaf extends DeletedChild {}")

        val tool = TypeHierarchyTool()
        val first = tool.execute(project, buildJsonObject {
            put("className", "pages.DeletedRoot")
            put("maxNodes", 1)
        })
        assertToolSucceeded("first hierarchy page should create a continuation", first)
        val cursor = requireNotNull(json.decodeFromString<TypeHierarchyResult>(toolText(first)).cursor)

        val rootPath = Path.of(requireNotNull(project.basePath), "src/pages/DeletedRoot.java")
        Files.delete(rootPath)
        LocalFileSystem.getInstance().refreshAndFindFileByPath(requireNotNull(project.basePath))
            ?.refresh(false, true)

        val next = tool.execute(project, buildJsonObject {
            put("cursor", cursor)
            put("maxNodes", 1)
        })

        assertToolFailed("continuation rooted at a deleted PSI element must fail", next)
        assertTrue(
            "failure should tell the client to restart the query: ${toolText(next)}",
            toolText(next).contains("deleted") || toolText(next).contains("invalid")
        )
    }

    private class CountingTypeHierarchyHandler(
        private val root: PsiClass,
        private val children: List<PsiClass>,
        private val repeatVisitedChildrenForLeaves: Boolean = false
    ) : TypeHierarchyHandler {
        override val languageId: String = "JAVA"
        var invocations: Int = 0
            private set

        override fun canHandle(element: PsiElement): Boolean = asClass(element) != null

        override fun isAvailable(): Boolean = true

        override fun getTypeHierarchy(
            element: PsiElement,
            project: Project,
            scope: BuiltInSearchScope,
            excludeGenerated: Boolean,
            directOnly: Boolean,
            direction: TypeHierarchyDirection?,
            page: HierarchyPageRequest?
        ): TypeHierarchyData? {
            invocations++
            val type = asClass(element) ?: return null
            val isRoot = type === root || type.manager.areElementsEquivalent(type, root)
            val directChildren = if (
                direction == TypeHierarchyDirection.SUBTYPE &&
                (isRoot || repeatVisitedChildrenForLeaves)
            ) {
                children.map(::toData)
            } else {
                emptyList()
            }
            val (pageChildren, nextOffset) = directChildren.applyHierarchyPage(page)
            return TypeHierarchyData(
                element = toData(type),
                supertypes = emptyList(),
                subtypes = pageChildren,
                nextOffset = nextOffset
            )
        }

        private fun asClass(element: PsiElement): PsiClass? =
            element as? PsiClass ?: PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)

        private fun toData(type: PsiClass): TypeElementData = TypeElementData(
            name = type.qualifiedName ?: type.name.orEmpty(),
            qualifiedName = type.qualifiedName,
            file = type.containingFile?.virtualFile?.path,
            line = 1,
            kind = if (type.isInterface) "INTERFACE" else "CLASS",
            language = "Java",
            pointerTarget = type
        )
    }

    private class CountingCallHierarchyHandler(
        private val root: PsiMethod,
        private val callees: List<PsiMethod>,
        private val repeatVisitedCalleesForLeaves: Boolean = false
    ) : CallHierarchyHandler {
        override val languageId: String = "JAVA"
        var invocations: Int = 0
            private set

        override fun canHandle(element: PsiElement): Boolean = asMethod(element) != null

        override fun isAvailable(): Boolean = true

        override fun getCallHierarchy(
            element: PsiElement,
            project: Project,
            direction: String,
            depth: Int,
            scope: BuiltInSearchScope,
            excludeGenerated: Boolean,
            page: HierarchyPageRequest?
        ): CallHierarchyData? {
            invocations++
            val method = asMethod(element) ?: return null
            val isRoot = method === root || method.manager.areElementsEquivalent(method, root)
            val directCallees = if (isRoot || repeatVisitedCalleesForLeaves) {
                callees.map(::toData)
            } else {
                emptyList()
            }
            val (pageCallees, nextOffset) = directCallees.applyHierarchyPage(page)
            return CallHierarchyData(
                element = toData(method),
                calls = pageCallees,
                nextOffset = nextOffset
            )
        }

        private fun asMethod(element: PsiElement): PsiMethod? =
            element as? PsiMethod ?: PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)

        private fun toData(method: PsiMethod): CallElementData = CallElementData(
            name = "${method.containingClass?.name}.${method.name}()",
            file = method.containingFile.virtualFile?.path ?: method.containingFile.name,
            line = 1,
            column = 1,
            language = "Java",
            pointerTarget = method
        )
    }
}
