package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.QueryExecutor
import org.junit.Assume

class JavaCallHierarchyPagingBehaviorTest : McpPlatformTestCase() {

    fun testCallerPagingCountsUniqueCallersInsteadOfRawReferenceSites() {
        Assume.assumeTrue("Java plugin required for this fixture", PluginDetectors.java.isAvailable)
        registerSourceRoot("src")
        val sourcePath = writeProjectFile(
            "src/paging/CallerSites.java",
            """
                package paging;

                class CallerSites {
                    void target() {}
                    void unrelated() {}

                    void noisy() {
                        unrelated(); unrelated(); unrelated(); unrelated();
                        unrelated(); unrelated(); unrelated(); unrelated();
                        unrelated(); unrelated();
                    }

                    void lateOne() { unrelated(); }
                    void lateTwo() { unrelated(); }
                }
            """.trimIndent()
        )
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath(sourcePath.toString())
        )
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as PsiJavaFile
        val psiClass = psiFile.classes.single()
        val target = psiClass.findMethodsByName("target", false).single()
        val noisy = psiClass.findMethodsByName("noisy", false).single()
        val lateOne = psiClass.findMethodsByName("lateOne", false).single()
        val lateTwo = psiClass.findMethodsByName("lateTwo", false).single()

        fun callReferencesIn(methodName: String): List<PsiReference> {
            val method = psiClass.findMethodsByName(methodName, false).single()
            return PsiTreeUtil.findChildrenOfType(
                requireNotNull(method.body),
                PsiMethodCallExpression::class.java
            )
                .sortedBy { it.textOffset }
                .map { requireNotNull(it.methodExpression.reference) }
        }

        val references = buildList {
            addAll(callReferencesIn(noisy.name))
            addAll(callReferencesIn(lateOne.name))
            addAll(callReferencesIn(lateTwo.name))
        }
        // The target has no real usages. Injecting through the platform's actual query EP gives
        // the handler a deterministic stream: ten distinct sites from one semantic caller first,
        // followed by two callers that the former `2 * maxResults` raw-site cap could not reach.
        val executor = QueryExecutor<PsiReference, MethodReferencesSearch.SearchParameters> { parameters, consumer ->
            parameters.method != target || references.all(consumer::process)
        }
        MethodReferencesSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)

        val handler = JavaCallHierarchyHandler()
        val first = requireNotNull(
            handler.getCallHierarchy(
                target,
                project,
                direction = "callers",
                depth = 1,
                scope = BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false,
                page = HierarchyPageRequest(offset = 0, limit = 2)
            )
        )

        assertEquals(
            "many sites in one method must not hide callers encountered later",
            listOf("CallerSites.noisy()", "CallerSites.lateOne()"),
            first.calls.map { it.name }
        )
        assertEquals("the third unique caller is the page look-ahead", 2, first.nextOffset)

        val second = requireNotNull(
            handler.getCallHierarchy(
                target,
                project,
                direction = "callers",
                depth = 1,
                scope = BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false,
                page = HierarchyPageRequest(offset = 2, limit = 2)
            )
        )
        assertEquals(listOf("CallerSites.lateTwo()"), second.calls.map { it.name })
        assertNull("the unique caller stream is exhausted", second.nextOffset)
    }
}
