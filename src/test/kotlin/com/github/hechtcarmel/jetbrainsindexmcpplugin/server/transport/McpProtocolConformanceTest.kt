package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpServerFactory
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.modelcontextprotocol.kotlin.sdk.client.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.net.BindException
import java.net.ServerSocket

/**
 * Drives the running server with the MCP Kotlin SDK's **client** over real HTTP.
 *
 * This is the strongest available statement that the wire contract is intact: an independent MCP
 * implementation performs the full handshake, lists the tools and calls one, with no shared code
 * path other than the protocol itself. It replaces the old `McpServerIntegrationTest`, which
 * asserted on hand-built JSON-RPC envelopes fed straight into the plugin's own handler — a test
 * that could not have caught a transport-level regression.
 */
class McpProtocolConformanceTest : BasePlatformTestCase() {

    private lateinit var scope: CoroutineScope
    private lateinit var server: KtorMcpServer
    private var port: Int = 0

    override fun setUp() {
        super.setUp()
        val registry = ToolRegistry().apply { registerBuiltInTools() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        startServerOnAvailablePort(registry)
    }

    override fun tearDown() {
        try {
            server.stop()
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testHandshakeReportsServerIdentityAndToolCapability() = withMcpClient { client ->
        val serverInfo = requireNotNull(client.serverVersion) { "initialize did not report serverInfo" }
        assertEquals(McpConstants.getServerName(), serverInfo.name)
        assertTrue("serverInfo.version must be the real plugin version", serverInfo.version.isNotBlank())
        assertNotSame("serverInfo.version must not be the old hardcoded constant", "4.10.4", serverInfo.version)

        assertNotNull("Server must advertise the tools capability", client.serverCapabilities?.tools)
        assertFalse(
            "listChanged must stay false until every transport can deliver tool-list notifications",
            client.serverCapabilities?.tools?.listChanged == true
        )
    }

    fun testInstructionsCarryTheServerDescription() = withMcpClient { client ->
        // The pre-migration server put this text in the non-standard `serverInfo.description`.
        // MCP's slot for it is `instructions`, which clients feed to the model.
        val instructions = requireNotNull(client.serverInstructions) { "Server did not send instructions" }
        assertTrue(
            "instructions should describe the refactoring capability, was: $instructions",
            instructions.contains("refactoring")
        )
    }

    fun testListToolsReturnsTheEnabledToolSurface() = withMcpClient { client ->
        val names = client.listTools().tools.map { it.name }.toSet()

        // ide_find_symbol and ide_file_structure are disabled by default, so are absent here.
        val expected = listOf(
            ToolNames.FIND_REFERENCES,
            ToolNames.FIND_DEFINITION,
            ToolNames.TYPE_HIERARCHY,
            ToolNames.CALL_HIERARCHY,
            ToolNames.FIND_IMPLEMENTATIONS,
            ToolNames.FIND_SUPER_METHODS,
            ToolNames.FIND_CLASS,
            ToolNames.FIND_FILE,
            ToolNames.SEARCH_TEXT,
            ToolNames.DIAGNOSTICS,
            ToolNames.INDEX_STATUS
        )
        expected.forEach { assertTrue("tools/list should contain $it", it in names) }
    }

    fun testEveryAdvertisedToolCarriesAnObjectInputSchema() = withMcpClient { client ->
        val tools = client.listTools().tools
        assertTrue("tools/list must not be empty", tools.isNotEmpty())

        tools.forEach { tool ->
            assertEquals("${tool.name} inputSchema must be an object schema", "object", tool.inputSchema.type)
            assertFalse("${tool.name} must have a description", tool.description.isNullOrBlank())
        }
    }

    fun testInitializeThenListToolsPublishesSemanticHandlesTargetsAndRefactoringPreview() {
        val settings = McpSettings.getInstance()
        val originalDisabled = settings.disabledTools
        settings.disabledTools = originalDisabled - setOf(
            ToolNames.SYMBOL_INFO,
            ToolNames.CHANGE_SIGNATURE
        )
        try {
            withMcpClient { client ->
                val tools = client.listTools().tools.associateBy { it.name }
                val semanticTools = setOf(
                    ToolNames.FIND_REFERENCES,
                    ToolNames.FIND_DEFINITION,
                    ToolNames.TYPE_HIERARCHY,
                    ToolNames.CALL_HIERARCHY,
                    ToolNames.FIND_IMPLEMENTATIONS,
                    ToolNames.FIND_SUPER_METHODS,
                    ToolNames.SYMBOL_INFO
                )
                for (toolName in semanticTools) {
                    val properties = schemaProperties(tools.getValue(toolName).inputSchema)
                    assertTrue("$toolName must publish symbolId in fresh tools/list", "symbolId" in properties)
                    assertTrue("$toolName must publish the additive nested target", "target" in properties)
                }

                for (toolName in setOf(
                    ToolNames.REFACTOR_RENAME,
                    ToolNames.REFACTOR_SAFE_DELETE,
                    ToolNames.CHANGE_SIGNATURE
                )) {
                    val properties = schemaProperties(tools.getValue(toolName).inputSchema)
                    assertTrue("$toolName must publish symbolId in fresh tools/list", "symbolId" in properties)
                    assertTrue("$toolName must publish dryRun in fresh tools/list", "dryRun" in properties)
                    assertTrue("$toolName must publish the additive nested target", "target" in properties)
                }
            }
        } finally {
            settings.disabledTools = originalDisabled
        }
    }

    fun testCallToolRoundTripsThroughTheProtocol() = withMcpClient { client ->
        val result = client.callTool(ToolNames.INDEX_STATUS, emptyMap())

        assertFalse("${ToolNames.INDEX_STATUS} should succeed: ${result.text}", result.isFailure)
        assertTrue("Result should carry content", result.content.isNotEmpty())
    }

    fun testUnknownToolIsReportedAsAToolErrorNotAProtocolError() = withMcpClient { client ->
        // Per the MCP spec, tool-level failures belong in the result so the model can read them.
        // A JSON-RPC error would surface to the user as a hard transport failure instead.
        val result = client.callTool("ide_does_not_exist", emptyMap())

        assertTrue("Unknown tool must come back as isError", result.isFailure)
        assertTrue(
            "Error text should name the tool, was: ${result.text}",
            result.text.contains("ide_does_not_exist")
        )
    }

    fun testPingSucceeds() = withMcpClient { client ->
        client.ping()
    }

    private fun withMcpClient(block: suspend (io.modelcontextprotocol.kotlin.sdk.client.Client) -> Unit) =
        runBlocking {
            val http = HttpClient(CIO)
            try {
                val client = http.mcpStreamableHttp(
                    "http://127.0.0.1:$port${McpConstants.STREAMABLE_HTTP_ENDPOINT_PATH}"
                )
                try {
                    block(client)
                } finally {
                    client.close()
                }
            } finally {
                http.close()
            }
        }

    private fun schemaProperties(schema: io.modelcontextprotocol.kotlin.sdk.types.ToolSchema) =
        McpJson.encodeToJsonElement(schema).jsonObject["properties"]!!.jsonObject

    /**
     * Closing ServerSocket(0) before Ktor binds necessarily leaves a short TOCTOU window. Retry
     * only genuine bind collisions so a busy ephemeral port cannot make the protocol gate flaky.
     */
    private fun startServerOnAvailablePort(registry: ToolRegistry) {
        var lastBindFailure: Throwable? = null
        repeat(10) {
            port = freePort()
            val candidate = KtorMcpServer(
                port = port,
                serverFactory = McpServerFactory(registry, McpToolDispatcher(registry)),
                legacySseTransports = LegacySseTransports(),
                coroutineScope = scope
            )
            val startResult = try {
                candidate.start()
            } catch (failure: Throwable) {
                candidate.stop()
                if (!failure.hasBindException()) throw failure
                lastBindFailure = failure
                return@repeat
            }
            when (startResult) {
                KtorMcpServer.StartResult.Success -> {
                    server = candidate
                    return
                }
                is KtorMcpServer.StartResult.PortInUse -> {
                    lastBindFailure = BindException("Port ${startResult.port} is already in use")
                }
                is KtorMcpServer.StartResult.Error -> {
                    val cause = startResult.cause
                    if (cause?.hasBindException() != true) {
                        candidate.stop()
                        fail("MCP protocol test server failed to start: ${startResult.message}")
                    }
                    lastBindFailure = cause
                }
            }
            candidate.stop()
        }
        fail("Could not reserve an MCP protocol test port after 10 attempts: ${lastBindFailure?.message}")
    }

    private fun Throwable.hasBindException(): Boolean =
        generateSequence(this) { it.cause }.take(16).any { it is BindException }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
