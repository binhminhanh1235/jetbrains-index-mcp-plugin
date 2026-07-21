package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.SuperMethodsData
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyData
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.types.TypeEvalContext
import io.mockk.every
import io.mockk.mockk
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PythonHierarchyHandlersTest : BasePlatformTestCase() {

    fun testTypeHierarchyIncludesPythonSupertypes() {
        val base = mockPyClass("BaseProcessor")
        val intermediate = mockPyClass("JsonProcessor")
        val leaf = mockPyClass("AdvancedJsonProcessor")

        every { leaf.getSuperClasses(any<TypeEvalContext>()) } returns arrayOf(intermediate)
        every { intermediate.getSuperClasses(any<TypeEvalContext>()) } returns arrayOf(base)
        every { base.getSuperClasses(any<TypeEvalContext>()) } returns emptyArray()

        val result = PythonTypeHierarchyHandler().getTypeHierarchy(leaf, project)

        assertNotNull("Type hierarchy should be returned", result)
        assertEquals(listOf("JsonProcessor"), result!!.supertypes.map { it.name })
        assertEquals(
            listOf("BaseProcessor"),
            result.supertypes.single().supertypes?.map { it.name }
        )
    }

    fun testFindSuperMethodsBuildsPythonHierarchy() {
        val baseClass = mockPyClass("BaseProcessor")
        val childClass = mockPyClass("JsonProcessor")
        val leafClass = mockPyClass("AdvancedJsonProcessor")

        val baseMethod = mockPyFunction("process", baseClass)
        val childMethod = mockPyFunction("process", childClass)
        val leafMethod = mockPyFunction("process", leafClass)

        every { leafClass.getSuperClasses(any<TypeEvalContext>()) } returns arrayOf(childClass)
        every { childClass.getSuperClasses(any<TypeEvalContext>()) } returns arrayOf(baseClass)
        every { baseClass.getSuperClasses(any<TypeEvalContext>()) } returns emptyArray()

        every { childClass.findMethodByName("process", false, any<TypeEvalContext>()) } returns childMethod
        every { baseClass.findMethodByName("process", false, any<TypeEvalContext>()) } returns baseMethod

        val result = PythonSuperMethodsHandler().findSuperMethods(leafMethod, project)

        assertHierarchyClasses(result, listOf("JsonProcessor", "BaseProcessor"))
        assertEquals(listOf(1, 2), result!!.hierarchy.map { it.depth })
    }

    private fun assertHierarchyClasses(result: SuperMethodsData?, expectedClasses: List<String>) {
        assertNotNull("Super methods data should be returned", result)
        assertEquals(expectedClasses, result!!.hierarchy.map { it.containingClass })
    }

    private fun mockPyClass(name: String): PyClass {
        return mockk(relaxed = true) {
            every { getName() } returns name
            every { getQualifiedName() } returns name
            every { getAncestorClasses(any<TypeEvalContext>()) } returns emptyList()
            every { getSuperClasses(any<TypeEvalContext>()) } returns emptyArray()
            every { findMethodByName(any(), any(), any<TypeEvalContext>()) } returns null
            every { parent } returns null
            every { containingFile } returns null
        }
    }

    private fun mockPyFunction(name: String, containingClass: PyClass): PyFunction {
        return mockk(relaxed = true) {
            every { getName() } returns name
            every { parent } returns containingClass
            every { containingFile } returns null
        }
    }
}
