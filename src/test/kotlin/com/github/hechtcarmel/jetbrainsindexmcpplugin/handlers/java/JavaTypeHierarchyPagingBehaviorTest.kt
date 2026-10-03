package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.HierarchyPageRequest
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.PsiTestUtil
import org.junit.Assume

class JavaTypeHierarchyPagingBehaviorTest : McpPlatformTestCase() {
    fun testResolvedInterfacesAreFilteredBeforeThePageLimit() {
        verifyLateProjectSupertypes(isInterface = false)
    }

    fun testExtendsFallbackDoesNotRelabelExcludedResolvedTypesAsUnresolved() {
        verifyLateProjectSupertypes(isInterface = true)
    }

    private fun verifyLateProjectSupertypes(isInterface: Boolean) {
        Assume.assumeTrue("Java plugin required", PluginDetectors.java.isAvailable)
        // Light fixtures reuse the project: keep source-folder registrations independent of
        // test order instead of recreating a previously registered root with another type.
        val fixturePrefix = if (isInterface) "interface-filtering" else "class-filtering"
        val productionPath = "$fixturePrefix-prod-src"
        val testPath = "$fixturePrefix-test-src"
        registerSourceRoot(productionPath)
        val testRoot = registerSourceRoot(testPath)
        PsiTestUtil.removeSourceRoot(module, testRoot)
        PsiTestUtil.addSourceRoot(module, testRoot, true)
        writeProjectFile("$productionPath/filtering/Visible.java", """
            package filtering;
            interface VisibleOne {}
            interface VisibleTwo {}
        """.trimIndent())
        val declaration = if (isInterface) "interface Root extends" else "class Root implements"
        writeProjectFile("$testPath/filtering/Root.java", """
            package filtering;
            interface HiddenOne {}
            interface HiddenTwo {}
            interface HiddenThree {}
            $declaration HiddenOne, HiddenTwo, HiddenThree, VisibleOne, VisibleTwo {}
        """.trimIndent())
        // A test-source seed can resolve both source sets, while the requested result scope
        // deliberately excludes its first three interfaces.
        val root = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "filtering.Root", GlobalSearchScope.projectScope(project)
        ))
        assertEquals("all interfaces must really resolve in this fixture", 5, root.interfaces.size)
        assertEquals("the fixture must expose the requested declaration kind", isInterface, root.isInterface)
        val resultScope = BuiltInSearchScopeResolver.resolveGlobalScope(project, BuiltInSearchScope.PROJECT_PRODUCTION_FILES)
        assertEquals(
            "the first three interfaces must actually be outside the result scope",
            listOf(false, false, false, true, true),
            root.interfaces.map { resultScope.contains(requireNotNull(it.containingFile.virtualFile)) }
        )
        val handler = JavaTypeHierarchyHandler()
        val first = requireNotNull(handler.getTypeHierarchy(
            root, project, BuiltInSearchScope.PROJECT_PRODUCTION_FILES, excludeGenerated = false,
            directOnly = true, direction = TypeHierarchyDirection.SUPERTYPE,
            page = HierarchyPageRequest(offset = 0, limit = 1)
        ))
        assertEquals(listOf("filtering.VisibleOne"), first.supertypes.map { it.qualifiedName })
        assertNotNull("an included declaration must retain its real pointer", first.supertypes.single().pointerTarget)
        assertEquals("the later included interface is look-ahead, not false exhaustion", 1, first.nextOffset)

        val second = requireNotNull(handler.getTypeHierarchy(
            root, project, BuiltInSearchScope.PROJECT_PRODUCTION_FILES, excludeGenerated = false,
            directOnly = true, direction = TypeHierarchyDirection.SUPERTYPE,
            page = HierarchyPageRequest(offset = 1, limit = 1)
        ))
        assertEquals(listOf("filtering.VisibleTwo"), second.supertypes.map { it.qualifiedName })
        assertNull(second.nextOffset)
    }
}
