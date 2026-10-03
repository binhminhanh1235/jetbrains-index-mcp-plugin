package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeHierarchyResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinHierarchyKindBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("The opt-in runtime must load the real Kotlin plugin", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("kind-src")
    }

    override fun tearDown() {
        try {
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testAbstractClassKeepsItsHierarchyKind() = assertKind("abstract class", "ABSTRACT_CLASS")
    fun testSealedClassKeepsItsHierarchyKind() = assertKind("sealed class", "ABSTRACT_CLASS")
    fun testInterfaceKeepsItsHierarchyKind() = assertKind("interface", "INTERFACE")
    fun testAnnotationKeepsItsHierarchyKind() = assertKind("annotation class", "ANNOTATION")
    fun testEnumKeepsItsHierarchyKind() = assertKind("enum class", "ENUM")
    fun testObjectKeepsItsHierarchyKind() = assertKind("object", "OBJECT")
    fun testConcreteClassKeepsItsHierarchyKind() = assertKind("class", "CLASS")

    private fun assertKind(declaration: String, expected: String) = runBlocking {
        writeProjectFile("kind-src/KindProbe.kt", "package kindprobe\n$declaration KindProbe {}")
        val result = TypeHierarchyTool().execute(project, buildJsonObject {
            put("file", "kind-src/KindProbe.kt")
            put("line", 2)
            put("column", declaration.length + 2)
        })
        assertToolSucceeded("Resolve real Kotlin declaration: $declaration", result)
        val hierarchy = Json { ignoreUnknownKeys = true }.decodeFromString<TypeHierarchyResult>(toolText(result))
        assertEquals(declaration, expected, hierarchy.element.kind)
    }
}
