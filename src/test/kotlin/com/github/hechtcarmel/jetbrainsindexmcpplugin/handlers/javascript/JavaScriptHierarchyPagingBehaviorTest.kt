package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.TypeHierarchyTool
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.QueryExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class JavaScriptHierarchyPagingBehaviorTest : McpPlatformTestCase() {
    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("js-paging-src")
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testSmallCallerPageStopsAfterEnoughUniqueDirectCallers() {
        val path = writeProjectFile("js-paging-src/callers.ts", buildString {
            appendLine("function target() {}")
            appendLine("function unrelated() {}")
            repeat(300) { appendLine("function caller$it() { unrelated(); }") }
        })
        val file = requireNotNull(LocalFileSystem.getInstance().findFileByPath(path.toString()))
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(file))
        val target = PsiTreeUtil.findChildrenOfType(psiFile, JSFunction::class.java).single { it.name == "target" }
        val references = PsiTreeUtil.findChildrenOfType(psiFile, JSReferenceExpression::class.java)
            .filter { it.text == "unrelated" }.sortedBy { it.textOffset }.map { it as PsiReference }
        assertEquals("Each injected reference belongs to a distinct caller", 300, references.size)
        var deliveries = 0
        val executor = QueryExecutor<PsiReference, ReferencesSearch.SearchParameters> { parameters, consumer ->
            parameters.elementToSearch != target || references.all {
                deliveries++
                consumer.process(it)
            }
        }
        ReferencesSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
        val page = requireNotNull(JavaScriptCallHierarchyHandler().getCallHierarchy(
            target, project, "callers", 1, BuiltInSearchScope.PROJECT_FILES, false,
            HierarchyPageRequest(offset = 0, limit = 1)
        ))
        assertEquals(1, page.calls.size)
        assertNotNull("The remaining unique callers need a continuation", page.nextOffset)
        assertTrue("A small page must stop the query after a bounded caller look-ahead; delivered $deliveries", deliveries <= 8)
    }

    fun testSameNamedTypesKeepTheirActualParentAcrossPages() = runBlocking {
        writeProjectFile("js-paging-src/base.ts", "export class A {}")
        writeProjectFile("js-paging-src/derived.ts", "import { A as Base } from './base'; export class A extends Base {}")
        writeProjectFile("js-paging-src/leaf.ts", "import { A } from './derived'; export class Leaf extends A {}")
        fun declaration(file: String): JSClass {
            val virtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/js-paging-src/$file"))
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
            return PsiTreeUtil.findChildrenOfType(psiFile, JSClass::class.java).single()
        }
        val basePsi = declaration("base.ts")
        val derivedPsi = declaration("derived.ts")
        val leafPsi = declaration("leaf.ts")
        assertTrue("The fixture must resolve the aliased base class", derivedPsi.superClasses.contains(basePsi))
        assertTrue("The fixture must resolve the intermediate class", leafPsi.superClasses.contains(derivedPsi))
        // Isolate the production direct-parent filter from the optional native inheritor index:
        // the platform query contract can return all transitive descendants.
        val executor = QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> { parameters, consumer ->
            val descendants = when (parameters.element) {
                basePsi -> listOf(derivedPsi, leafPsi)
                derivedPsi -> listOf(leafPsi)
                else -> emptyList()
            }
            descendants.all(consumer::process)
        }
        DefinitionsScopedSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
        val subtypes = mutableListOf<TypeElement>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = TypeHierarchyTool().execute(project, buildJsonObject {
                val continuation = cursor
                if (continuation == null) {
                    put("file", "js-paging-src/base.ts")
                    put("line", 1)
                    put("column", 14)
                    put("maxNodes", 1)
                } else {
                    put("cursor", continuation)
                }
            })
            assertToolSucceeded("Page through actual TypeScript subtype identities", result)
            val page = Json { ignoreUnknownKeys = true }.decodeFromString<TypeHierarchyResult>(toolText(result))
            subtypes.addAll(page.subtypes)
            cursor = page.cursor
            assertTrue("Traversal must terminate", ++pages <= 5)
        } while (cursor != null)
        assertEquals("The complete subtype result is $subtypes", 2, subtypes.size)
        val derived = subtypes.single { it.file == "js-paging-src/derived.ts" }
        val leaf = subtypes.single { it.file == "js-paging-src/leaf.ts" }
        assertEquals(1, derived.depth)
        assertEquals("A transitive descendant is not a direct subtype of an equally named ancestor", 2, leaf.depth)
        assertEquals(derived.nodeId, leaf.parentId)
    }
}
