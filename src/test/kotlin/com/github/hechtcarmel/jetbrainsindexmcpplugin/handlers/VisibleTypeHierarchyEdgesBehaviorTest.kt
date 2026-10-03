package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python.PythonTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php.PhpTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import org.junit.Assume
import java.nio.file.Files
import java.nio.file.Path

/**
 * Real PSI supplies declaration identity and source scopes; an explicit parent graph supplies the
 * native relationships. These tests execute the production traversal and Python query collector,
 * without claiming to exercise an installed Python/PHP index or impersonating optional plugin PSI.
 */
class VisibleTypeHierarchyEdgesBehaviorTest : McpPlatformTestCase() {
    private lateinit var productionRoot: String
    private lateinit var testRoot: String
    private val testScope: GlobalSearchScope
        get() = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_TEST_FILES)

    override fun setUp() {
        super.setUp()
        Assume.assumeTrue("Java PSI supplies language-neutral declaration fixtures", PluginDetectors.java.isAvailable)
        // The light fixture reuses its project. Do not remove/recreate the same source-root URLs
        // between methods, or briefly register a test directory as production source content.
        productionRoot = "visible-${getTestName(true)}/prod"
        testRoot = "visible-${getTestName(true)}/tests"
        registerSourceRoot(productionRoot)
        val testPath = Path.of(requireNotNull(project.basePath), testRoot)
        Files.createDirectories(testPath)
        val tests = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(testPath.toString()))
        PsiTestUtil.addSourceRoot(module, tests, true)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testIncludedLeafRemainsReachableAcrossAnExcludedIntermediate() {
        val root = declaration("Root")
        val middle = declaration("Middle")
        val leaf = declaration("Leaf", inTests = true)
        val graph = mapOf(leaf to listOf(middle), middle to listOf(root))

        assertFalse(shouldIncludeNavigationElement(testScope, root))
        assertFalse(shouldIncludeNavigationElement(testScope, middle))
        assertTrue(shouldIncludeNavigationElement(testScope, leaf))
        assertTrue(isVisibleSubtypeOf(leaf, root, testScope) { graph[it].orEmpty() })
    }

    fun testIncludedIntermediateKeepsItsOwnEdge() {
        val root = declaration("Root")
        val middle = declaration("Middle", inTests = true)
        val leaf = declaration("Leaf", inTests = true)
        val graph = mapOf(leaf to listOf(middle), middle to listOf(root))
        val expanded = mutableListOf<PsiElement>()

        assertFalse(isVisibleSubtypeOf(leaf, root, testScope) {
            expanded.add(it)
            graph[it].orEmpty()
        })
        assertEquals("Do not walk through an included intermediate", listOf(leaf), expanded)
        assertTrue(isVisibleSubtypeOf(leaf, middle, testScope) { graph[it].orEmpty() })
        assertTrue(isVisibleSubtypeOf(middle, root, testScope) { graph[it].orEmpty() })
    }

    fun testEquallyNamedDeclarationsInDifferentFilesAreNotTheSameAncestor() {
        val actualParent = declaration("ActualParent", name = "A")
        val unrelatedParent = declaration("UnrelatedParent", name = "A")
        val leaf = declaration("Leaf", inTests = true)
        val graph = mapOf(leaf to listOf(actualParent))

        assertTrue(isVisibleSubtypeOf(leaf, actualParent, testScope) { graph[it].orEmpty() })
        assertFalse(isVisibleSubtypeOf(leaf, unrelatedParent, testScope) { graph[it].orEmpty() })
    }

    fun testExcludedBranchOfADiamondRemainsReachableBesideAnIncludedBranch() {
        val root = declaration("Root")
        val includedMiddle = declaration("IncludedMiddle", inTests = true)
        val excludedMiddle = declaration("ExcludedMiddle")
        val leaf = declaration("Leaf", inTests = true)
        val graph = mapOf(
            leaf to listOf(includedMiddle, excludedMiddle),
            includedMiddle to listOf(root),
            excludedMiddle to listOf(root)
        )

        assertTrue(isVisibleSubtypeOf(leaf, root, testScope) { graph[it].orEmpty() })
        assertTrue(isVisibleSubtypeOf(leaf, includedMiddle, testScope) { graph[it].orEmpty() })
    }

    fun testExcludedParentCycleTerminatesWithoutFindingAnUnrelatedRoot() {
        val root = declaration("Root")
        val first = declaration("First")
        val second = declaration("Second")
        val leaf = declaration("Leaf", inTests = true)
        val graph = mapOf(leaf to listOf(first), first to listOf(second), second to listOf(first))
        val expanded = mutableListOf<PsiElement>()

        assertFalse(isVisibleSubtypeOf(leaf, root, testScope) {
            expanded.add(it)
            graph[it].orEmpty()
        })
        assertEquals("Each excluded ancestor is expanded once", listOf(leaf, first, second), expanded)
    }

    fun testPythonCollectorStopsAfterTheAcceptedPageLookahead() {
        val root = declaration("Root")
        val excludedMiddle = declaration("ExcludedMiddle")
        val includedMiddle = declaration("IncludedMiddle", inTests = true)
        val indirect = declaration("Indirect", inTests = true)
        val first = declaration("First", inTests = true)
        val second = declaration("Second", inTests = true)
        val remaining = declaration("Remaining", inTests = true)
        val graph = mapOf(
            excludedMiddle to listOf(root),
            includedMiddle to listOf(root),
            indirect to listOf(includedMiddle),
            first to listOf(excludedMiddle),
            second to listOf(excludedMiddle),
            remaining to listOf(excludedMiddle)
        )
        val candidates = listOf(excludedMiddle, indirect, first, second) + List(300) { remaining }
        var deliveries = 0
        val query = object : AbstractQuery<PsiElement>() {
            override fun processResults(consumer: Processor<in PsiElement>): Boolean = candidates.all {
                deliveries++
                consumer.process(it)
            }
        }
        val pageRequest = HierarchyPageRequest(offset = 0, limit = 1)
        val collected = PythonTypeHierarchyHandler().collectSubtypes(
            project,
            testScope,
            pageRequest.collectionLimit,
            includeCandidate = { candidate ->
                isVisibleSubtypeOf(candidate, root, testScope) { graph[it].orEmpty() }
            }
        ) { query }

        assertEquals("Rejected candidates must not consume the result budget", listOf(first, second), collected.map { it.pointerTarget })
        assertEquals("Stop the native query at the accepted lookahead", 4, deliveries)
        val (page, nextOffset) = collected.applyHierarchyPage(pageRequest)
        assertEquals(listOf(first), page.map { it.pointerTarget })
        assertEquals(1, nextOffset)
    }

    fun testPhpBoundedQueryRequestsDeepDescendants() {
        val root = declaration("Root")
        var queryRequests = 0
        val executor = QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> { parameters, _ ->
            if (parameters.element === root) {
                queryRequests++
                assertTrue("PHP must ask the native provider for deep descendants", parameters.isCheckDeep)
            }
            true
        }
        DefinitionsScopedSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
        val handler = PhpTypeHierarchyHandler()
        val method = handler.javaClass.declaredMethods.single { it.name == "getSubtypes" }.apply { isAccessible = true }
        // This enters the real bounded query path before plugin-specific candidate classification.
        // The query executor above observes its public parameters, not PHP index completeness.
        method.invoke(handler, project, root, testScope, true, 2, true)
        assertEquals("The production PHP bounded search must actually reach its executor", 1, queryRequests)
    }

    fun testCancellationDuringAncestryTraversalDoesNotReturnAPartialPythonPage() {
        val root = declaration("Root")
        val excludedMiddle = declaration("ExcludedMiddle")
        val first = declaration("First", inTests = true)
        val second = declaration("Second", inTests = true)
        val graph = mapOf(first to listOf(root), second to listOf(excludedMiddle), excludedMiddle to listOf(root))
        val query = object : AbstractQuery<PsiElement>() {
            override fun processResults(consumer: Processor<in PsiElement>): Boolean =
                listOf(first, second).all(consumer::process)
        }
        val indicator = EmptyProgressIndicator()
        var completed = false
        var cancellationRequested = false
        try {
            ProgressManager.getInstance().runProcess(Runnable {
                PythonTypeHierarchyHandler().collectSubtypes(
                    project,
                    testScope,
                    10,
                    includeCandidate = { candidate ->
                        isVisibleSubtypeOf(candidate, root, testScope) { parent ->
                            if (parent === excludedMiddle) {
                                cancellationRequested = true
                                indicator.cancel()
                            }
                            graph[parent].orEmpty()
                        }
                    }
                ) { query }
                completed = true
            }, indicator)
            fail("An interrupted traversal must not return its first collected subtype")
        } catch (_: ProcessCanceledException) {
            assertTrue("The query must reach the second candidate's excluded parent", cancellationRequested)
            assertFalse("No successful partial page may escape", completed)
        }
    }

    private fun declaration(id: String, inTests: Boolean = false, name: String = id): PsiElement {
        val root = if (inTests) testRoot else productionRoot
        val path = writeProjectFile("$root/$id.java", "class $name {}")
        val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        assertTrue("Fixture file must be in source content: $path", fileIndex.isInSourceContent(file))
        assertEquals("Fixture test-source classification must match its requested scope: $path", inTests, fileIndex.isInTestSourceContent(file))
        return (PsiManager.getInstance(project).findFile(file) as PsiJavaFile).classes.single()
    }
}
