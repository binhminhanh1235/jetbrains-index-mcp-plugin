package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.history.CommandStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp.McpToolDispatcher
import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.isFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.text
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicBoolean

class McpToolDispatcherTimeoutTest : McpPlatformTestCase() {

    fun testTimedOutSearchReturnsActionableErrorAndFinishesHistory() = runBlocking {
        val completed = AtomicBoolean()
        val tool = TestTool {
            try {
                awaitCancellation()
            } finally {
                completed.set(true)
            }
        }
        var status: CommandStatus? = null
        val dispatcher = dispatcher(tool) { status = it }
        val result = withTimeout(5_000L) { dispatcher.call(tool.name, arguments()) }

        assertTrue(result.isFailure)
        assertTrue(result.text.contains("timed out"))
        assertTrue(result.text.contains("ide_index_status"))
        assertEquals(CommandStatus.ERROR, status)
        assertTrue("The timed-out operation must finish before replying", completed.get())
    }

    fun testTimedOutQueuedEditDoesNotRunWhenEdtBecomesAvailable() = runBlocking {
        val wrote = AtomicBoolean()
        val tool = TestTool { queuedWrite { wrote.set(true) }; success() }
        val dispatcher = dispatcher(tool)

        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val call = scope.async { dispatcher.call(tool.name, arguments()) }
        try {
            // Hold EDT without pumping, but release it on failure too so a regression fails
            // this assertion instead of hanging the entire test JVM.
            val result = withTimeoutOrNull(2_000L) { call.await() }
            assertNotNull("Request cancellation must finish without pumping EDT", result)
            assertTrue(result!!.isFailure)
            assertTrue(result.text.contains("timed out"))
        } finally {
            scope.cancel()
            UIUtil.dispatchAllInvocationEvents()
            call.join()
        }
        assertFalse("A cancelled queued edit must never execute later", wrote.get())
    }

    fun testCallerCancellationIsNotConvertedToTimeoutError() = runBlocking {
        val cancellation = CancellationException("caller cancelled")
        val tool = TestTool { throw cancellation }
        try {
            dispatcher(tool).call(tool.name, arguments())
            fail("Caller cancellation must propagate")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
        }
    }

    fun testQueuedEditPreservesRequestThreadContext() = runBlocking {
        val requestValue = ThreadLocal<String?>()
        val observed = arrayOfNulls<String>(1)
        val tool = TestTool {
            queuedWrite {
                assertTrue(ApplicationManager.getApplication().isDispatchThread)
                observed[0] = requestValue.get()
            }
            success()
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val call = scope.async {
            withContext(requestValue.asContextElement("request-epoch")) {
                tool.execute(project, arguments())
            }
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!call.isCompleted && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                delay(10L)
            }
            assertTrue("The queued edit must complete", call.isCompleted)
            assertFalse(call.await().isFailure)
            assertEquals("request-epoch", observed[0])
        } finally {
            scope.cancel()
            UIUtil.dispatchAllInvocationEvents()
        }
    }

    fun testLongPollToolKeepsItsOwnBudgetAndOperationId() = runBlocking {
        val tool = TestTool(ToolNames.BUILD_PROJECT) {
            delay(250L)
            CallToolResult(content = listOf(TextContent("running: build-id")))
        }
        val settings = McpSettings.getInstance()
        val disabledTools = settings.disabledTools
        try {
            settings.setToolEnabled(tool.name, true)
            val result = dispatcher(tool).call(tool.name, arguments())
            assertFalse(result.text, result.isFailure)
            assertEquals("running: build-id", result.text)
        } finally {
            settings.disabledTools = disabledTools
        }
    }

    private fun arguments() = buildJsonObject { put("project_path", project.basePath) }

    private fun dispatcher(tool: TestTool, onStatus: (CommandStatus) -> Unit = {}) = McpToolDispatcher(
        toolRegistry = ToolRegistry().apply { register(tool) },
        edtUnresponsiveDurationMs = { null },
        recordHistory = { _, _ -> },
        updateHistory = { _, _, status, _, _ -> onStatus(status) },
        executionTimeoutMs = 100L
    )

    private class TestTool(
        override val name: String = "ide_test_timeout",
        private val action: suspend TestTool.() -> CallToolResult
    ) : AbstractMcpTool() {
        override val description = "Test request deadline"
        override val inputSchema = ToolSchema()
        override val requiresPsiSync = false
        override val participatesInLifecycle = false

        override suspend fun doExecute(project: Project, arguments: JsonObject) = action()

        suspend fun queuedWrite(action: () -> Unit) = edtAction(action)
        fun success() = CallToolResult(content = listOf(TextContent("done")))
    }
}
