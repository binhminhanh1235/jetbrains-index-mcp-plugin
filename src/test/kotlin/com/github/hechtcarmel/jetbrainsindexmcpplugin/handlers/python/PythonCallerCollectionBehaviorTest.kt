package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.applyHierarchyPage
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import org.junit.Assume

/** Tests the real post-native-lookup collector without impersonating optional Python PSI types. */
class PythonCallerCollectionBehaviorTest : McpPlatformTestCase() {
    fun testExcludedPrefixDoesNotHideLateUniqueCallers() {
        Assume.assumeTrue("Java PSI is used as language-neutral named declaration fixtures", PluginDetectors.java.isAvailable)
        registerSourceRoot("prod-src")
        val testRoot = registerSourceRoot("test-src")
        PsiTestUtil.removeSourceRoot(module, testRoot)
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        writeProjectFile("test-src/callers/Excluded.java", """
            package callers;
            class Excluded {
                void first() {} void second() {} void third() {}
                void fourth() {} void fifth() {} void sixth() {}
            }
        """.trimIndent())
        writeProjectFile("prod-src/callers/First.java", "package callers; class First { void call() {} }")
        writeProjectFile("prod-src/callers/Second.java", "package callers; class Second { void call() {} }")
        writeProjectFile("prod-src/callers/Third.java", "package callers; class Third { void call() {} }")

        fun methods(owner: String): List<PsiMethod> = requireNotNull(
            JavaPsiFacade.getInstance(project).findClass("callers.$owner", GlobalSearchScope.projectScope(project))
        ).methods.toList()

        val included = listOf("First", "Second", "Third").map { methods(it).single() }
        val candidates = methods("Excluded") + List(5) { included[0] } + included.drop(1)
        val pageRequest = HierarchyPageRequest(offset = 0, limit = 2)
        val results = PythonCallHierarchyHandler().collectIncludedCallers(
            project = project,
            // Mirrors the native API's semantic callable candidates. The shared PSI fields used
            // here (file, name, offset, scope) do not require an installed Python plugin.
            directCallers = candidates.asSequence(),
            depth = 1,
            visited = mutableSetOf(),
            stackDepth = 0,
            searchScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_PRODUCTION_FILES),
            maxResults = pageRequest.collectionLimit
        )
        val (first, nextOffset) = results.applyHierarchyPage(pageRequest)
        assertEquals("same short name in different files is three distinct callers", 3, results.size)
        assertEquals(listOf(included[0], included[1]), first.map { it.pointerTarget })
        assertEquals(2, nextOffset)
        val (second, lastOffset) = results.applyHierarchyPage(HierarchyPageRequest(offset = 2, limit = 2))
        assertEquals(listOf(included[2]), second.map { it.pointerTarget })
        assertNull(lastOffset)
    }
}
