package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinSemanticMemberEditingBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("semantic-src")
        writeProjectFile("semantic-src/Service.kt", """
            package lighttarget
            class Service {
                fun compute(): Int { return 1 }
                fun keep(): Int { return 7 }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    private fun source(): String = ReadAction.compute<String, Throwable> {
        readProjectFileVfs("semantic-src/Service.kt")
    }

    fun testEditMemberQualifiedJavaSelectorEditsKotlinSource() = runBlocking {
        val result = EditMemberTool().execute(project, buildJsonObject {
            put("target", buildJsonObject {
                put("qualifiedName", "lighttarget.Service#compute()")
                put("language", "Java")
            })
            put("content", "fun compute(): Int { return 42 }")
            put("reformat", false)
        })
        assertToolSucceeded("Java light method must resolve to an editable Kotlin declaration", result)
        assertTrue(source().contains("fun compute(): Int { return 42 }"))
        assertFalse(source().contains("return 1"))
        assertTrue(source().contains("fun keep(): Int { return 7 }"))
    }

    fun testReplaceMemberQualifiedJavaSelectorEditsKotlinBody() = runBlocking {
        val result = ReplaceMemberTool().execute(project, buildJsonObject {
            put("target", buildJsonObject {
                put("qualifiedName", "lighttarget.Service#compute()")
                put("language", "Java")
            })
            put("content", " return 42 ")
            put("reformat", false)
        })
        assertToolSucceeded("Java light method must resolve to the Kotlin body", result)
        assertTrue(source().contains("fun compute(): Int { return 42 }"))
        assertFalse(source().contains("return 1"))
        assertTrue(source().contains("fun keep(): Int { return 7 }"))
    }
}
