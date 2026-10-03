package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.exceptions.IndexNotReadyException
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandHistoryService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.EdtHeartbeatService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerEpoch
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Everything that happens between "a client asked for tool X" and "the tool ran".
 *
 * The MCP protocol itself — JSON-RPC framing, `initialize`, version negotiation, `tools/list`
 * dispatch — belongs to the SDK. What is left is IDE-specific and lives here:
 *
 *  1. the enabled/disabled gate from Settings → Tools → Index MCP Server → Exposed Tools
 *  2. resolving `project_path` to an open [Project], reopening it if the lifecycle manager
 *     closed it
 *  3. recording the call in the per-project command history shown in the tool window
 *  4. running the tool inside an IDE modality context
 *
 * ### Why the modality context is here and not in the HTTP layer
 *
 * Ktor worker threads carry no IntelliJ modality state, and
 * [com.intellij.psi.PsiDocumentManager.commitAllDocuments] requires a write-safe context.
 * Before the SDK migration this wrapper sat around the whole HTTP handler; the SDK owns that
 * now, so it wraps the narrower and more correct thing: the tool invocation itself.
 */
class McpToolDispatcher @JvmOverloads constructor(
    private val toolRegistry: ToolRegistry,
    private val edtUnresponsiveDurationMs: () -> Long? = Companion::defaultEdtCheck,
    private val recordHistory: (Project, CommandEntry) -> Unit = { project, entry ->
        CommandHistoryService.getInstance(project).recordCommand(entry)
    },
    private val updateHistory: (Project, String, CommandStatus, String?, Long?) -> Unit = { project, id, status, result, duration ->
        CommandHistoryService.getInstance(project).updateCommandStatus(id, status, result, duration)
    },
    private val symbolIdRegistryProvider: () -> SymbolIdRegistry = SymbolIdRegistry::getInstance,
    private val serverEpochProvider: () -> McpServerEpoch = { McpServerEpoch.shared },
    private val executionTimeoutMs: Long = 55_000L
) {

    private companion object {
        val LOG = logger<McpToolDispatcher>()

        /**
         * History is for display, not replay. `ide_find_references` on a popular class can return
         * 100 KB+, and every entry would otherwise sit in the per-project deque.
         */
        const val HISTORY_RESULT_LIMIT = 4096
        // These tools own their wait budget and must return an operation id for the next poll.
        private val LONG_POLL_TOOLS = setOf(
            ToolNames.BUILD_PROJECT, ToolNames.RUN_TESTS, ToolNames.PROJECT_DIAGNOSTICS,
            ToolNames.OPEN_PROJECT, ToolNames.OPEN_WORKSPACE
        )
        private fun defaultEdtCheck(): Long? {
            val app = ApplicationManager.getApplication() ?: return null
            if (app.isUnitTestMode) return null
            return EdtHeartbeatService.getInstance().edtUnresponsiveDurationMs()
        }
    }

    /**
     * Runs [toolName] with [arguments].
     *
     * Never throws for tool-level problems: per the MCP spec, tool failures are reported in the
     * result with `isError = true` so the model can read and react to them, rather than as
     * JSON-RPC errors which clients treat as hard transport failures. Cancellation still
     * propagates.
     */
    suspend fun call(toolName: String, arguments: JsonObject): CallToolResult {
        val serverEpoch = serverEpochProvider()
        val requestEpoch = serverEpoch.capture()
        return withContext(serverEpoch.requestContext(requestEpoch)) {
            callInEpoch(toolName, arguments)
        }
    }

    /** Executes every request phase against the one epoch captured by [call]. */
    private suspend fun callInEpoch(
        toolName: String,
        arguments: JsonObject
    ): CallToolResult {
        val serverEpoch = serverEpochProvider()
        val requestEpoch = serverEpoch.expectedForCurrentRequest()
        val tool = toolRegistry.getTool(toolName)
            ?: return CallToolResult.error(ErrorMessages.toolNotFound(toolName))

        if (!McpSettings.getInstance().isToolEnabled(toolName)) {
            return CallToolResult.error(
                "Tool '$toolName' is disabled. Enable it in Settings → Tools → Index MCP Server → Exposed Tools."
            )
        }

        val unresponsiveMs = edtUnresponsiveDurationMs()
        if (unresponsiveMs != null) {
            return CallToolResult.error(
                "IDE UI thread has been unresponsive for ${unresponsiveMs / 1000}s — " +
                    "tool calls requiring the IDE are unavailable. Consider restarting the IDE."
            )
        }

        // A pre-execution callback can cross a server restart boundary. Reject this request
        // before resolving a project or allowing any registry-backed work to continue.
        if (!serverEpoch.isCurrent(requestEpoch)) {
            return CallToolResult.error("The MCP server session changed. Retry the request.")
        }

        val projectPathElement = arguments[ParamNames.PROJECT_PATH]
        if (projectPathElement != null &&
            projectPathElement !is JsonNull &&
            (projectPathElement as? JsonPrimitive)?.isString != true
        ) {
            return CallToolResult.error("${ParamNames.PROJECT_PATH} must be a string when present")
        }
        val projectPath = (projectPathElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        val schemaProperties = tool.inputSchema.properties
        val acceptsSymbolId = schemaProperties?.containsKey(ParamNames.SYMBOL_ID) == true
        val acceptsUnifiedTarget = UnifiedTargetArguments.isSupportedBy(tool.inputSchema)
        // Cursor continuation owns target resolution. Do not route a paged request through
        // unrelated symbol selectors that a client may have left in the argument object.
        val ownsCursorContinuation = arguments.containsKey(ParamNames.CURSOR) &&
            schemaProperties?.containsKey(ParamNames.CURSOR) == true
        val executionArguments = if (ownsCursorContinuation) {
            JsonObject(arguments - ParamNames.SYMBOL_ID - UnifiedTargetArguments.TARGET)
        } else arguments

        val symbolId = if (ownsCursorContinuation || (!acceptsSymbolId && !acceptsUnifiedTarget)) {
            null
        } else {
            UnifiedTargetArguments.symbolIdForRouting(
                arguments = arguments,
                acceptsLegacySymbolId = acceptsSymbolId,
                acceptsNestedTarget = acceptsUnifiedTarget
            ).getOrElse {
                return CallToolResult.error(it.message ?: "Invalid symbolId target")
            }
        }

        val project = if (projectPath == null && symbolId != null) {
            symbolIdRegistryProvider().projectFor(symbolId).getOrElse {
                return CallToolResult.error(it.message ?: ErrorMessages.symbolIdExpired(symbolId))
            }
        } else {
            val projectResult = ProjectResolver.resolveOrOpen(projectPath)
            if (projectResult.isError) return projectResult.errorResult!!
            projectResult.project!!
        }

        val commandEntry = CommandEntry(toolName = toolName, parameters = arguments)
        recordHistorySafely(project, commandEntry)

        val startTime = System.currentTimeMillis()
        return try {
            val result = withIdeModality {
                if (toolName in LONG_POLL_TOOLS) {
                    tool.execute(project, executionArguments)
                } else {
                    withTimeoutOrNull(executionTimeoutMs) {
                        tool.execute(project, executionArguments)
                    }
                }
            } ?: return failed(
                project, commandEntry, startTime,
                "Tool '$toolName' timed out while waiting for the IDE or running the operation. " +
                    "Close any modal dialog, wait for ide_index_status to report isDumbMode=false, " +
                    "and retry with a narrower scope if searching. " +
                    "For an edit, inspect the target file before retrying: a write already in progress may have completed."
            )
            updateHistorySafely(
                project = project,
                commandEntry = commandEntry,
                status = if (result.isError == true) CommandStatus.ERROR else CommandStatus.SUCCESS,
                result = result.summarizeForHistory(),
                duration = System.currentTimeMillis() - startTime
            )
            result
        } catch (e: ProcessCanceledException) {
            // IntelliJ control-flow exception (e.g. project disposed mid-call). Must not be
            // logged as an error and must not become a tool result.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: IndexNotReadyException) {
            // Dumb mode is expected during indexing — debug, not error.
            LOG.debug("Tool $toolName called while IDE is indexing: ${e.message}")
            failed(project, commandEntry, startTime, e.message ?: "IDE index is not ready")
        } catch (e: Exception) {
            LOG.error("Tool execution failed: $toolName", e)
            failed(project, commandEntry, startTime, e.message ?: ErrorMessages.UNKNOWN_ERROR)
        }
    }

    private fun failed(
        project: Project,
        commandEntry: CommandEntry,
        startTime: Long,
        message: String
    ): CallToolResult {
        updateHistorySafely(
            project = project,
            commandEntry = commandEntry,
            status = CommandStatus.ERROR,
            result = message,
            duration = System.currentTimeMillis() - startTime
        )
        return CallToolResult.error(message)
    }

    private fun CallToolResult.summarizeForHistory(): String? =
        when (val block = content.firstOrNull()) {
            is TextContent -> block.text.take(HISTORY_RESULT_LIMIT)
            is ImageContent -> "[Image]"
            null -> null
            else -> "[${block::class.simpleName}]"
        }

    private suspend fun <T> withIdeModality(block: suspend () -> T): T {
        // Null in plain unit tests that never boot the platform.
        ApplicationManager.getApplication() ?: return block()
        return withContext(ModalityState.any().asContextElement()) { block() }
    }

    private fun recordHistorySafely(project: Project, commandEntry: CommandEntry) {
        try {
            recordHistory(project, commandEntry)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to record command history for ${commandEntry.toolName}", e)
        }
    }

    private fun updateHistorySafely(
        project: Project,
        commandEntry: CommandEntry,
        status: CommandStatus,
        result: String?,
        duration: Long
    ) {
        try {
            updateHistory(project, commandEntry.id, status, result, duration)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Failed to update command history for ${commandEntry.toolName}", e)
        }
    }
}
