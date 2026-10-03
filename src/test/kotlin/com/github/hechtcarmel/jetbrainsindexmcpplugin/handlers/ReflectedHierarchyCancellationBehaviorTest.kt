package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.go.GoTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.javascript.JavaScriptTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.php.PhpTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python.PythonTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.rust.RustTypeHierarchyHandler
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.QueryExecutor
import kotlinx.coroutines.CancellationException
import java.lang.reflect.InvocationTargetException

/** These execute production collectors and reflective calls, without impersonating an optional plugin. */
class ReflectedHierarchyCancellationBehaviorTest : McpPlatformTestCase() {
    fun testPythonDoesNotReturnAPartialSubtypeLevelOnCancellation() = assertPythonCancellation(ProcessCanceledException())
    fun testPythonDoesNotReturnAPartialSubtypeLevelOnCoroutineCancellation() = assertPythonCancellation(CancellationException("cancelled"))
    fun testPythonDoesNotReturnAPartialSubtypeLevelWhenIndexesBecomeUnavailable() = assertPythonCancellation(IndexNotReadyException.create())

    private fun assertPythonCancellation(expected: RuntimeException) {
        val candidate = (myFixture.addFileToProject("Candidate.java", "class Candidate {}") as PsiJavaFile).classes.single()
        var visitedCandidate = false
        val query = object : AbstractQuery<PsiElement>() {
            override fun processResults(consumer: Processor<in PsiElement>): Boolean {
                visitedCandidate = true
                assertTrue(consumer.process(candidate))
                throw InvocationTargetException(expected)
            }
        }
        assertPropagated(expected) {
            PythonTypeHierarchyHandler().collectSubtypes(project, GlobalSearchScope.allScope(project), 10) { query }
        }
        assertTrue("The real collector must have started its query", visitedCandidate)
    }

    fun testPhpHierarchyPropagatesReflectedControlFlow() = assertReflectedHierarchy(PhpTypeHierarchyHandler(), "getSupertypes")
    fun testRustHierarchyPropagatesReflectedControlFlow() = assertReflectedHierarchy(RustTypeHierarchyHandler(), "getSupertraitHierarchy")
    fun testJavaScriptHierarchyPropagatesReflectedControlFlow() = assertReflectedHierarchy(JavaScriptTypeHierarchyHandler(), "getSupertypes")

    private fun assertReflectedHierarchy(handler: Any, methodName: String) {
        val method = handler.javaClass.declaredMethods.single { it.name == methodName }.apply { isAccessible = true }
        for (expected in listOf(ProcessCanceledException(), CancellationException("cancelled"), IndexNotReadyException.create())) {
            val target = InterruptedHierarchyElement(expected)
            assertPropagated(expected) {
                method.invoke(handler, project, target, mutableSetOf<String>(), 0, GlobalSearchScope.allScope(project), true)
            }
            assertTrue("The actual plugin API call must have been attempted", target.wasRead)
        }
    }

    fun testGoSubtypeSearchPropagatesCancellation() {
        val target = (myFixture.addFileToProject("GoTarget.java", "class GoTarget {}") as PsiJavaFile).classes.single()
        val expected = ProcessCanceledException()
        val executor = QueryExecutor<PsiElement, DefinitionsScopedSearch.SearchParameters> { _, _ -> throw expected }
        DefinitionsScopedSearch.EP_NAME.point.registerExtension(executor, testRootDisposable)
        val handler = GoTypeHierarchyHandler()
        val method = handler.javaClass.declaredMethods.single { it.name == "getSubtypes" }.apply { isAccessible = true }
        assertPropagated(expected) {
            method.invoke(handler, project, target, null, GlobalSearchScope.allScope(project), 10)
        }
    }

    private fun assertPropagated(expected: RuntimeException, action: () -> Any?) {
        try {
            val result = action()
            fail("${expected.javaClass.simpleName} must propagate, not return a complete hierarchy: $result")
        } catch (actual: Exception) {
            // Remove only the wrapper added by this test's private-method invocation.
            val failure = if (actual is InvocationTargetException) actual.cause else actual
            assertSame("Preserve the original control-flow exception", expected, failure)
        }
    }
}

internal class InterruptedHierarchyElement(private val failure: RuntimeException) : FakePsiElement() {
    var wasRead = false
    override fun getParent(): PsiElement? = null
    override fun getName(): String = "InterruptedType"
    fun getFQN(): String = "InterruptedType"
    fun getQualifiedName(): String = "InterruptedType"
    fun getSuperClass(): PsiElement? = interrupted()
    fun getSuperClasses(): Array<PsiElement> = interrupted()
    fun getSuperTraits(): List<PsiElement> = interrupted()
    private fun interrupted(): Nothing {
        wasRead = true
        throw InvocationTargetException(failure)
    }
}
