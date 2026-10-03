package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DefinitionResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.FileStructureResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/** Check that outline handles still identify the outlined element after lookup. */
class OutlineHandleRoundTripBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        SymbolIdRegistry.getInstance().resetSession()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("outline-review")
    }

    override fun tearDown() {
        try {
            SymbolIdRegistry.getInstance().resetSession()
            LanguageHandlerRegistry.clear()
        } finally {
            super.tearDown()
        }
    }

    fun testJavaRecordAnnotationAndEnumHandlesRoundTrip() = runBlocking {
        val file = "outline-review/OutlineKinds.java"
        writeProjectFile(file, """
            @interface Marker { String value(); }
            enum Choice { FIRST, SECOND; int number() { return ordinal(); } }
            record Point(int x, int y) {}
            class Envelope { class Nested {} }
        """.trimIndent())
        val payload = assertOutlineRoundTrips(file)
        assertEquals(
            listOf("ANNOTATION", "ENUM", "RECORD", "CLASS"),
            payload.nodes.map { it.kind.name }
        )
    }

    fun testMarkdownHeadingHandlesRoundTrip() = runBlocking {
        assumeTrue("Markdown runtime required", PluginDetectors.markdown.isAvailable)
        val file = "outline-review/guide.md"
        writeProjectFile(file, "# Title\n## Nested\nText\nSecond title\n============\n")
        assertOutlineRoundTrips(file)
    }

    fun testTypeScriptHandlesRoundTrip() = runBlocking {
        assumeTrue("JavaScript runtime required", PluginDetectors.javaScript.isAvailable)
        val file = "outline-review/types.ts"
        writeProjectFile(file, """
            export type Key = string;
            export interface Sink { send(value: string): void; }
            export class Writer {
              field = 0;
              get value(): number { return this.field; }
              set value(next: number) { this.field = next; }
              choose(value: number): void; choose(value: string): void;
              choose(value: number | string): void {}
            }
            export const arrow = (value: string) => value;
            export function top(value: number) { return value; }
        """.trimIndent())
        assertOutlineRoundTrips(file)
    }

    private suspend fun assertOutlineRoundTrips(file: String): FileStructureResult {
        val result = FileStructureTool().execute(project, buildJsonObject {
            put("file", file)
            put("includeNodes", true)
            put("includeSymbolIds", true)
        })
        assertToolSucceeded("extract $file", result)
        val payload = json.decodeFromString<FileStructureResult>(toolText(result))
        val nodes = flatten(payload.nodes)
        assertTrue("The fixture must actually produce nodes", nodes.isNotEmpty())
        for (node in nodes) {
            val handle = node.symbolId ?: continue
            val before = ReadAction.compute<com.intellij.psi.PsiElement, RuntimeException> {
                SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow()
            }
            val definition = FindDefinitionTool().execute(project, buildJsonObject {
                put("symbolId", handle)
                put("fullElementPreview", true)
            })
            assertToolSucceeded("definition round trip for ${node.kind} ${node.name}", definition)
            val located = json.decodeFromString<DefinitionResult>(toolText(definition))
            assertEquals("definition should stay on the outlined source line: ${node.name}", node.line, located.line)
            ReadAction.run<RuntimeException> {
                val after = SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow()
                assertSame("lookup must not rebind an outline handle to another element: ${node.name}", before, after)
            }
        }
        return payload
    }

    private fun flatten(nodes: List<StructureNode>): List<StructureNode> =
        nodes.flatMap { listOf(it) + flatten(it.children) }
}
