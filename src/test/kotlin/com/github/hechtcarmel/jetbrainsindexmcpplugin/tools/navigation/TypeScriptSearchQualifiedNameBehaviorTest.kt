package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindClassTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.FindSymbolTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindClassResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FindSymbolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TypeScriptSearchQualifiedNameBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun setUp() {
        super.setUp()
        assertTrue("Real JavaScript plugin is required for this probe", PluginDetectors.javaScript.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("review-qualified-src")
        writeProjectFile("review-qualified-src/QualifiedTarget.ts", """
            namespace AdoptionProbeNamespace {
                export class AdoptionQualifiedTarget {
                    adoptionQualifiedMethod(): number { return 1; }
                }
            }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testFindClassRetainsTypeScriptQualifiedName() = runBlocking {
        val result = FindClassTool().execute(project, buildJsonObject {
            put("query", "AdoptionQualifiedTarget")
        })
        assertToolSucceeded("Find the TypeScript class", result)
        val match = json.decodeFromString<FindClassResult>(toolText(result)).classes.single { it.name == "AdoptionQualifiedTarget" }
        assertEquals("AdoptionProbeNamespace.AdoptionQualifiedTarget", match.qualifiedName)
    }

    fun testFindSymbolRetainsTypeScriptQualifiedName() = runBlocking {
        val result = FindSymbolTool().execute(project, buildJsonObject {
            put("query", "AdoptionQualifiedTarget")
        })
        assertToolSucceeded("Find the TypeScript symbol", result)
        val match = json.decodeFromString<FindSymbolResult>(toolText(result)).symbols.single { it.name == "AdoptionQualifiedTarget" }
        assertEquals("AdoptionProbeNamespace.AdoptionQualifiedTarget", match.qualifiedName)
    }
}
