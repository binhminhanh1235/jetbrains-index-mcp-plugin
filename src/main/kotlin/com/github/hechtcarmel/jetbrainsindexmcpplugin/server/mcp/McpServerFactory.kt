package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.EmptyJsonObject
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Builds the MCP [Server] that the transports hand connections to.
 *
 * A fresh [Server] is created per connection (per POST, for the stateless Streamable HTTP
 * endpoint) for two reasons:
 *
 *  - **Settings take effect immediately.** [ToolRegistry.getToolDefinitions] filters by the
 *    enabled/disabled setting, so a tool toggled in Settings appears or disappears from the very
 *    next `tools/list` without restarting the server. A long-lived [Server] would snapshot the
 *    tool list once at startup.
 *  - **No session accumulation.** `Server` keeps a map of live sessions; in stateless mode
 *    nothing ever closes them. Callers must close the server when the call completes — see
 *    `KtorMcpServer`.
 *
 * Construction is cheap: it fills two maps from an already-built tool list.
 *
 * `open` so `StatelessServerLifecycleTest` can observe the servers handed to the transport and
 * prove they are closed again — that leak is otherwise invisible from outside.
 */
open class McpServerFactory(
    private val toolRegistry: ToolRegistry,
    private val dispatcher: McpToolDispatcher,
    parentDisposable: Disposable? = null
) {

    @Volatile
    private var cachedTools: List<RegisteredTool>? = null

    init {
        val app = ApplicationManager.getApplication()
        if (app != null) {
            val connection = if (parentDisposable != null) {
                app.messageBus.connect(parentDisposable)
            } else {
                app.messageBus.connect()
            }
            connection.subscribe(McpSettings.TOPIC, McpSettings.ChangeListener { cachedTools = null })
        }
    }

    fun invalidateCache() {
        cachedTools = null
    }

    private val serverInfo by lazy {
        Implementation(
            name = McpConstants.getServerName(),
            version = McpConstants.getServerVersion(),
            title = McpConstants.PLUGIN_NAME
        )
    }

    open fun newServer(): Server {
        val server = Server(
            serverInfo = serverInfo,
            options = SERVER_OPTIONS,
            // `Implementation` has no `description` field — MCP puts this kind of "how to use
            // this server" text in `instructions`, which clients surface to the model.
            instructions = McpConstants.SERVER_DESCRIPTION
        )
        server.addTools(registeredTools())
        return server
    }

    internal fun registeredTools(): List<RegisteredTool> =
        cachedTools ?: buildToolList().also { cachedTools = it }

    private fun buildToolList(): List<RegisteredTool> =
        toolRegistry.getToolDefinitions().map { tool ->
            RegisteredTool(tool) { request ->
                dispatcher.call(request.params.name, request.params.arguments ?: EmptyJsonObject)
            }
        }

    companion object {
        private val SERVER_OPTIONS = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    }
}
