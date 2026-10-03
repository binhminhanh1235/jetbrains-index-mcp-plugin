package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.QueryExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Real TS declarations and scope; only the underlying definition stream is controlled. */
class TypeScriptHierarchyScopeBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        HierarchyContinuationRegistry.getInstance().resetSession()
        registerSourceRoot("bridge-probe-prod")
        val testRoot = registerSourceRoot("bridge-probe-tests")
        PsiTestUtil.removeSourceRoot(module, testRoot)
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        writeProjectFile("bridge-probe-prod/root.ts", "export class Root {}")
        writeProjectFile("bridge-probe-prod/middle.ts", "import { Root } from './root'; export class Middle extends Root {}")
        writeProjectFile("bridge-probe-tests/leaf.ts", "import { Middle } from '../bridge-probe-prod/middle'; export class Leaf extends Middle {}")
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    override fun tearDown() {
        try {
            HierarchyContinuationRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testPagedTypeScriptHierarchyKeepsIncludedDescendantBehindExcludedParent() = runBlocking {
        fun declaration(file: String): JSClass {
            val virtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$file"))
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            return PsiTreeUtil.findChildrenOfType(psiFile, JSClass::class.java).single()
        }
        val root = declaration("bridge-probe-prod/root.ts")
        val middle = declaration("bridge-probe-prod/middle.ts")
        val leaf = declaration("bridge-probe-tests/leaf.ts")
        assertTrue("Middle must resolve Root", middle.superClasses.contains(root))
        assertTrue("Leaf must resolve Middle", leaf.superClasses.contains(middle))
        val resultScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_TEST_FILES)
        assertFalse(resultScope.contains(requireNotNull(middle.containingFile.virtualFile)))
        assertTrue(resultScope.contains(requireNotNull(leaf.containingFile.virtualFile)))
        var deliveredRootDescendants = 0
        val executor = QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> { parameters, consumer ->
            val descendants = when (parameters.element) {
                root -> listOf(middle, leaf)
                middle -> listOf(leaf)
                else -> emptyList()
            }
            descendants.filter { resultScope.contains(requireNotNull(it.containingFile.virtualFile)) }.all {
                if (parameters.element == root) deliveredRootDescendants++
                consumer.process(it)
            }
        }
        DefinitionsScopedSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
        val tool = TypeHierarchyTool()
        val legacyResult = tool.execute(project, buildJsonObject {
            put("file", "bridge-probe-prod/root.ts")
            put("line", 1)
            put("column", 14)
            put("scope", "project_test_files")
        })
        assertToolSucceeded("Legacy query must see the test leaf", legacyResult)
        val legacy = json.decodeFromString<TypeHierarchyResult>(toolText(legacyResult))
        assertEquals(setOf("bridge-probe-tests/leaf.ts"), legacy.subtypes.map { it.file }.toSet())
        deliveredRootDescendants = 0
        val pagedSubtypes = mutableListOf<TypeElement>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = tool.execute(project, buildJsonObject {
                val currentCursor = cursor
                if (currentCursor == null) {
                    put("file", "bridge-probe-prod/root.ts")
                    put("line", 1)
                    put("column", 14)
                    put("scope", "project_test_files")
                } else {
                    put("cursor", currentCursor)
                }
                put("maxNodes", 1)
            })
            assertToolSucceeded("Paged query must retain the included test descendant", result)
            val page = json.decodeFromString<TypeHierarchyResult>(toolText(result))
            pagedSubtypes += page.subtypes
            cursor = page.cursor
            assertTrue("Probe traversal must terminate", ++pages <= 8)
        } while (cursor != null)
        assertTrue("The real collector must consume the controlled descendant stream", deliveredRootDescendants > 0)
        assertEquals(legacy.subtypes.map { it.file }.toSet(), pagedSubtypes.map { it.file }.toSet())
        assertEquals("The excluded parent is collapsed into the visible root edge", "n0", pagedSubtypes.single().parentId)
    }
}
