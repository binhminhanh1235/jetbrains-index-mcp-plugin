package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AnonymousImplementationMetadataBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testAnonymousImplementationHasUsefulLocationNameAndNoInventedQualifiedName() = runBlocking {
        registerSourceRoot("anonymous-impl-src")
        writeProjectFile(
            "anonymous-impl-src/anonimpl/Task.java",
            """
            package anonimpl;

            public interface Task {
                void run();
            }
            """.trimIndent()
        )
        writeProjectFile(
            "anonymous-impl-src/anonimpl/TaskFactory.java",
            """
            package anonimpl;

            public final class TaskFactory {
                public Task create() {
                    return new Task() {
                        @Override public void run() {}
                    };
                }
            }
            """.trimIndent()
        )
        writeProjectFile(
            "anonymous-impl-src/anonimpl/NamedTask.java",
            """
            package anonimpl;

            public final class NamedTask implements Task {
                @Override public void run() {}
            }
            """.trimIndent()
        )

        val result = FindImplementationsTool().execute(project, buildJsonObject {
            put("file", "anonymous-impl-src/anonimpl/Task.java")
            put("line", 3)
            put("column", 18)
        })
        assertToolSucceeded("find_implementations should include anonymous classes", result)

        val implementations = json.decodeFromString<ImplementationResult>(toolText(result)).implementations
        val anonymous = implementations.singleOrNull { it.name.startsWith("<anonymous implementation of Task at ") }
        assertNotNull("Expected an anonymous Task implementation, got: ${implementations.map { it.name }}", anonymous)
        assertEquals(
            "<anonymous implementation of Task at TaskFactory.java:5>",
            anonymous!!.name
        )
        assertNull(
            "Anonymous implementations must expose qualifiedName = null on the wire",
            anonymous.qualifiedName
        )

        val named = implementations.singleOrNull { it.qualifiedName == "anonimpl.NamedTask" }
        assertNotNull("Named class implementations should expose their semantic qualified name", named)
        assertEquals("anonimpl.NamedTask", named!!.name)

        val symbolId = requireNotNull(anonymous.symbolId)
        val qualifiedName = ReadAction.compute<String?, RuntimeException> {
            val element = SymbolIdRegistry.getInstance().resolve(project, symbolId).getOrThrow()
            PsiUtils.qualifiedName(element.navigationElement)
        }
        assertNull("Anonymous implementations must not get a fabricated qualified name", qualifiedName)

        val methodResult = FindImplementationsTool().execute(project, buildJsonObject {
            put("file", "anonymous-impl-src/anonimpl/Task.java")
            put("line", 4)
            put("column", 10)
        })
        assertToolSucceeded("find_implementations should include overriding methods", methodResult)

        val methods = json.decodeFromString<ImplementationResult>(toolText(methodResult)).implementations
        val namedMethod = methods.singleOrNull { it.name == "NamedTask.run" }
        assertNotNull("Expected NamedTask.run implementation, got: ${methods.map { it.name }}", namedMethod)
        assertNull(
            "Method implementations have no reliable shared qualified-name contract",
            namedMethod!!.qualifiedName
        )
    }
}
