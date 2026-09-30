package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.LongPoll
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsInProgressResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector.extractTestRunnerResultsViewer
import com.intellij.execution.*
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.CompositePrintable
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.ui.TestResultsViewer
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.testframework.TestSearchScope
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.util.messages.MessageBusConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RunTestsTool : AbstractMcpTool() {

    /** How a single ide_run_tests call should behave: start a new run, or attach to a live one. */
    internal sealed interface RequestMode {
        data class Start(val target: String) : RequestMode
        data class StartTargets(val targets: List<String>) : RequestMode
        data class StartPackage(val packageName: String) : RequestMode
        data class StartDirectory(val directory: String) : RequestMode
        data class StartModule(val module: String) : RequestMode
        data class Attach(val runId: String) : RequestMode
        data class Invalid(val message: String) : RequestMode
    }

    companion object {
        private val LOG = logger<RunTestsTool>()
        private const val DEFAULT_TIMEOUT_SECONDS = 120

        /** Grace period to let the IDE's test tree finalize after the process exits. Normally instant. */
        private val TEST_TREE_FINALIZE_TIMEOUT = 10.seconds

        /** See [LongPoll] for why every call must return well under the MCP client's own timeout. */
        internal const val DEFAULT_WAIT_SECONDS = LongPoll.DEFAULT_WAIT_SECONDS
        internal const val MAX_WAIT_SECONDS = LongPoll.MAX_WAIT_SECONDS

        /** Exactly one scope selector or runId must be provided to start or poll a run. */
        internal fun resolveRequestMode(arguments: JsonObject): RequestMode {
            val target = LongPoll.optionalTrimmedString(arguments, ParamNames.TARGET)
            val runId = LongPoll.optionalTrimmedString(arguments, ParamNames.RUN_ID)
            val packageName = LongPoll.optionalTrimmedString(arguments, ParamNames.PACKAGE)
            val directory = LongPoll.optionalTrimmedString(arguments, ParamNames.DIRECTORY)
            val moduleName = LongPoll.optionalTrimmedString(arguments, ParamNames.MODULE)

            val targetsElement = arguments[ParamNames.TARGETS]
            val targetsList: List<String>? = if (targetsElement != null) {
                val array = targetsElement as? JsonArray
                    ?: return RequestMode.Invalid("'targets' must be an array of strings.")
                if (array.isEmpty()) {
                    return RequestMode.Invalid("targets array must not be empty.")
                }
                val list = array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
                if (list.isEmpty()) {
                    return RequestMode.Invalid("targets array must not be empty.")
                }
                if (list.size > 50) {
                    return RequestMode.Invalid("targets array must contain at most 50 entries (received ${list.size}).")
                }
                list
            } else {
                null
            }

            val presentSelectors = mutableListOf<String>()
            if (target != null) presentSelectors.add(ParamNames.TARGET)
            if (targetsList != null) presentSelectors.add(ParamNames.TARGETS)
            if (packageName != null) presentSelectors.add(ParamNames.PACKAGE)
            if (directory != null) presentSelectors.add(ParamNames.DIRECTORY)
            if (moduleName != null) presentSelectors.add(ParamNames.MODULE)
            if (runId != null) presentSelectors.add(ParamNames.RUN_ID)

            return when {
                presentSelectors.size > 1 -> {
                    val selectorsStr = presentSelectors.joinToString(" and ") { "'$it'" }
                    RequestMode.Invalid(
                        "Provide either a single test scope selector ('target', 'targets', 'package', 'directory', 'module') to start a run, or 'runId' to poll a running one, not multiple ($selectorsStr)."
                    )
                }
                target != null -> RequestMode.Start(target)
                targetsList != null -> RequestMode.StartTargets(targetsList)
                packageName != null -> RequestMode.StartPackage(packageName)
                directory != null -> RequestMode.StartDirectory(directory)
                moduleName != null -> RequestMode.StartModule(moduleName)
                runId != null -> RequestMode.Attach(runId)
                else -> RequestMode.Invalid(
                    "Either a test scope selector ('target', 'targets', 'package', 'directory', 'module') to start a run, or 'runId' to poll a running one, is required."
                )
            }
        }

        internal fun resolveWaitSeconds(arguments: JsonObject): Int = LongPoll.resolveWaitSeconds(arguments)

        /**
         * Floor for [finalizeWaitMillis]: collecting the test tree after a late exit still gets a
         * short grace even when the wait budget is spent, because the run is removed right after
         * collection — returning empty-handed would drop the results permanently.
         */
        private const val MIN_FINALIZE_WAIT_MS = 3_000L

        /**
         * The tree-finalize grace must fit in what remains of the call's wait budget, or a process
         * exit landing near the end of the window stacks wait + finalize past the MCP client's
         * request timeout — the client aborts, and the just-collected-and-removed results are lost.
         */
        internal fun finalizeWaitMillis(waitSeconds: Int, callStartMs: Long, nowMs: Long): Long {
            val budgetLeftMs = waitSeconds * 1000L - (nowMs - callStartMs)
            return minOf(TEST_TREE_FINALIZE_TIMEOUT.inWholeMilliseconds, maxOf(MIN_FINALIZE_WAIT_MS, budgetLeftMs))
        }

        /** Console-output collection normally completes in milliseconds; this is the wedged-executor ceiling. */
        private val OUTPUT_COLLECTION_TIMEOUT = 5.seconds

        /**
         * Deliberately tiny: this floor stacks on top of the [MIN_FINALIZE_WAIT_MS] floor when
         * the wait budget is already spent, and at the max `waitSeconds` (55s) the call is then
         * only ~2s from the MCP client's 60s default timeout — blowing it loses the results
         * permanently (the run is removed on collection). 250ms still catches the normal case,
         * where the alarm queue drains in single-digit milliseconds; a busier queue costs the
         * output fields, never the results.
         */
        private const val MIN_OUTPUT_WAIT_MS = 250L

        /**
         * Wait ceiling for console-output collection, bounded like [finalizeWaitMillis] by what
         * remains of the call's wait budget. Past-budget overshoot is at most
         * [MIN_OUTPUT_WAIT_MS] on top of the finalize floor — see [MIN_OUTPUT_WAIT_MS] for why
         * it must stay small. The floor is smaller than the finalize floor because dropping
         * output only degrades the result, while dropping the test tree loses it — results are
         * still returned either way.
         */
        internal fun outputWaitMillis(waitSeconds: Int, callStartMs: Long, nowMs: Long): Long {
            val budgetLeftMs = waitSeconds * 1000L - (nowMs - callStartMs)
            return minOf(OUTPUT_COLLECTION_TIMEOUT.inWholeMilliseconds, maxOf(MIN_OUTPUT_WAIT_MS, budgetLeftMs))
        }

        /**
         * [processStarted] distinguishes the run's two phases (issue #348): while the IDE is
         * still building, `timeoutSeconds` has not started counting and the agent should keep
         * polling instead of concluding the run is stuck — the message must say so.
         */
        internal fun buildInProgressResult(
            runId: String,
            configName: String,
            elapsedSeconds: Long,
            timeoutSeconds: Int,
            processStarted: Boolean
        ): RunTestsInProgressResult = RunTestsInProgressResult(
            status = "running",
            runId = runId,
            configName = configName,
            elapsedSeconds = elapsedSeconds,
            timeoutSeconds = timeoutSeconds,
            message = if (processStarted) {
                "Test run '$configName' is still executing (${elapsedSeconds}s elapsed, " +
                        "${timeoutSeconds}s limit). The run continues in the IDE. Call ide_run_tests again " +
                        "with {\"runId\": \"$runId\"} to keep waiting for its results (include the same " +
                        "project_path if you provided one)."
            } else {
                "The IDE is still preparing test run '$configName' (compiling / running before-launch " +
                        "tasks; ${elapsedSeconds}s elapsed). The test process has not started yet — the " +
                        "${timeoutSeconds}s timeoutSeconds limit only begins once it does. Call ide_run_tests " +
                        "again with {\"runId\": \"$runId\"} to keep waiting (include the same project_path " +
                        "if you provided one)."
            }
        )

        /**
         * Parses a target string into a class name and optional method name.
         * - `"com.example.MyTest"` → `("com.example.MyTest", null)`
         * - `"com.example.MyTest#testFoo"` → `("com.example.MyTest", "testFoo")`
         * - `"com.example.MyTest#"` → `("com.example.MyTest", null)` (blank method)
         * - `"All Tests"` (no `#`) → `("All Tests", null)` (run-config name passthrough)
         */
        internal fun parseTarget(target: String): Pair<String, String?> {
            if (target.contains('#')) {
                val parts = target.split('#', limit = 2)
                return parts[0] to parts[1].takeIf { it.isNotBlank() }
            }
            return target to null
        }

        /**
         * Defaults to false: agent-driven runs return results in the response, so popping the Run
         * tool window only steals the user's focus (issue #278). The run content is still added to
         * the tool window — it just isn't activated.
         */
        internal fun shouldActivateToolWindow(arguments: JsonObject): Boolean =
            arguments[ParamNames.ACTIVATE_TOOL_WINDOW]?.jsonPrimitive?.booleanOrNull ?: false

        internal fun shouldIncludeSuccessOutput(arguments: JsonObject): Boolean =
            arguments[ParamNames.INCLUDE_SUCCESS_OUTPUT]?.jsonPrimitive?.booleanOrNull ?: false

        /**
         * `ExecutionManagerImpl` copies the run configuration's activate/focus flags onto the
         * descriptor and then invokes the environment callback, before `RunContentManagerImpl`
         * reads [RunContentDescriptor.isActivateToolWindowWhenAdded] to decide whether to open the
         * Run tool window. Overriding the flags here therefore suppresses activation for this run
         * only, without mutating the user's persisted run configuration
         * (`activateToolWindowBeforeRun`).
         */
        internal fun suppressToolWindowActivation() = ProgramRunner.Callback { descriptor ->
            descriptor?.isActivateToolWindowWhenAdded = false
            descriptor?.isAutoFocusContent = false
        }
    }

    override val name = ToolNames.RUN_TESTS

    override val description = """
        Run tests using the IDE's run configuration infrastructure and return structured results.

        The test scope can be specified using one of:
        - target: an existing run configuration name (e.g. "All Tests") — works for ANY language/framework;
          or a fully qualified class name (e.g. "com.example.MyTest") — Java/Kotlin only;
          or a class and method separated by '#' (e.g. "com.example.MyTest#testFoo") — Java/Kotlin only.
        - targets: batch array of fully qualified class names or class#method (max 50 entries) — runs all
          specified classes in a single test run — Java/Kotlin only.
        - package: fully qualified package name (e.g. "com.example.service") — runs all tests in the package — Java/Kotlin.
        - directory: relative path to test source directory (e.g. "src/test/kotlin") — works for Java/Kotlin, Python, JS, Go.
        - module: IntelliJ module name — runs all tests in that module.

        Long-running runs: each call blocks at most waitSeconds (default $DEFAULT_WAIT_SECONDS) so your MCP client's
        request timeout is never hit. If the run is still going when the wait budget ends — whether
        the IDE is still compiling before the test process starts, or the tests themselves are still
        executing — the call returns {"status": "running", "runId": "..."} while the run continues
        inside the IDE; call this tool again with that runId (and no target) to keep waiting. The
        run itself is bounded by timeoutSeconds, counted from when the test process starts (build
        time before that is not billed to the run): once it expires the process is killed and the
        next poll reports timedOut: true.

        Returns: success status, exit code, pass/fail/error counts, and per-test results. Each test
        carries its console output (stdout/stderr merged in print order, as the IDE's test console
        shows them), and the top-level "output" field carries output not attributed to any test
        (framework/suite messages, @BeforeAll/@AfterAll prints, build-runner log lines, and prints
        from a test killed mid-run — e.g. at timeoutSeconds — which gets no per-test entry). Failed or
        errored tests include errorMessage and stackTrace (very long traces are trimmed in the
        middle, keeping the throw site and the root cause). On mass failures per-run size budgets
        apply: earlier failures keep their traces, later entries carry errorMessage only, and
        per-test output stops attaching once its own budget is spent.
        Results are read directly from the IDE's test runner, so they reflect this run (not stale report
        files) and work with any Service-Message-based framework (JUnit, TestNG, pytest, Jest, Go test, PHPUnit).

        Parameters:
        - project_path (optional): required when multiple projects are open.
        - target: existing run config name, fully qualified class (com.example.MyTest), or class#method
          (com.example.MyTest#testFoo).
        - targets: array of fully qualified class names or class#method (max 50 entries) to run in a single run.
        - package: fully qualified package name to run all tests in that package.
        - directory: relative path to test source directory to run all tests in that directory.
        - module: IntelliJ module name to run all tests in that module.
        - runId: id from a previous {"status": "running"} response; attaches to that run and keeps waiting.
        Exactly one of target, targets, package, directory, module, or runId is required.
        - timeoutSeconds (optional, default $DEFAULT_TIMEOUT_SECONDS): maximum seconds the test RUN may take before its
          process is killed, counted from when the test process starts. Applies to the whole run,
          across polls; ignored when runId is given.
        - waitSeconds (optional, default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS): maximum seconds THIS CALL may block before
          returning results or a "running" status. Keep it below your MCP client's request timeout.
        - activateToolWindow (optional, default false): open the Run tool window for this run. By default
          the run stays in the background without stealing focus; its content is still added to the Run
          tool window for manual inspection.
        - includeSuccessOutput (optional, default false): include console output for passed tests (and
          successful run-level output). By default, console output is omitted for passed tests to save
          tokens, but retained for failed/errored tests. Set to true to retrieve console output for all tests.

        Example: {"target": "com.example.MyTest", "timeoutSeconds": 7200}, then if a "running" status
        comes back: {"runId": "<runId from that response>"}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(
            ParamNames.TARGET,
            "Test target: existing run config name, fully qualified class (com.example.MyTest), or " +
                    "class#method (com.example.MyTest#testFoo). Exactly one test selector ('target', " +
                    "'targets', 'package', 'directory', 'module') or 'runId' is required."
        )
        .stringArrayProperty(
            ParamNames.TARGETS,
            "Batch test targets: array of fully qualified class names or class#method (max 50 entries). " +
                    "Runs all specified test classes in a single test run. Exactly one test selector is required."
        )
        .stringProperty(
            ParamNames.PACKAGE,
            "Package scope: fully qualified package name (e.g. 'com.example.service'). " +
                    "Runs all tests in the package. Exactly one test selector is required."
        )
        .stringProperty(
            ParamNames.DIRECTORY,
            "Directory scope: relative path to test source directory (e.g. 'src/test/kotlin'). " +
                    "Runs all tests in that directory. Exactly one test selector is required."
        )
        .stringProperty(
            ParamNames.MODULE,
            "Module scope: IntelliJ module name. Runs all tests in that module. " +
                    "Exactly one test selector is required."
        )
        .stringProperty(
            ParamNames.RUN_ID,
            "runId from a previous {\"status\": \"running\"} response: attaches to that run and keeps " +
                    "waiting instead of starting a new one. Exactly one test selector or 'runId' is required."
        )
        .intProperty(
            ParamNames.TIMEOUT_SECONDS,
            "Maximum seconds the whole test run may take before its process is killed (counted from " +
                    "test process start, enforced across polls). Default: $DEFAULT_TIMEOUT_SECONDS. Ignored when runId is given."
        )
        .intProperty(
            ParamNames.WAIT_SECONDS,
            "Maximum seconds this call may block before returning results or a \"running\" status. " +
                    "Default: $DEFAULT_WAIT_SECONDS, max: $MAX_WAIT_SECONDS. Keep below your MCP client's request timeout."
        )
        .booleanProperty(
            ParamNames.ACTIVATE_TOOL_WINDOW,
            "Open (activate) the Run tool window for this run. Default: false — the run executes in " +
                    "the background without stealing focus; its content is still added to the Run tool window."
        )
        .booleanProperty(
            ParamNames.INCLUDE_SUCCESS_OUTPUT,
            "Include console output for passed/successful tests (and successful run-level output). " +
                    "Default: false — console output is omitted for passed tests to save tokens, but " +
                    "retained for failed/errored tests. Set to true to retrieve console output for all tests."
        )
        .build()

    /** Attach polls (`runId`) read no PSI until final collection — skip the per-call sync tax. */
    override fun needsPsiSync(arguments: JsonObject): Boolean =
        LongPoll.optionalTrimmedString(arguments, ParamNames.RUN_ID) == null

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val callStartMs = System.currentTimeMillis()
        val waitSeconds = resolveWaitSeconds(arguments)
        val includeSuccessOutput = shouldIncludeSuccessOutput(arguments)

        return when (val mode = resolveRequestMode(arguments)) {
            is RequestMode.Invalid -> createErrorResult(mode.message)

            is RequestMode.Attach -> {
                val run = ActiveTestRunRegistry.getInstance(project).get(mode.runId)
                    ?: return createErrorResult(
                        "No active test run with id '${mode.runId}'. Its results may have already been " +
                                "collected, the run may have been evicted after completing, or the IDE was " +
                                "restarted. Start a new run by passing 'target' instead."
                    )
                awaitRunResult(project, run, waitSeconds, callStartMs, includeSuccessOutput)
            }

            is RequestMode.Start -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val runConfiguration = resolveRunConfiguration(project, mode.target)
                    ?: return createErrorResult(
                        "Could not find or create a run configuration for target '${mode.target}'. " +
                                "Provide an existing run configuration name or a fully qualified Java/Kotlin class name."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs,
                    includeSuccessOutput
                )
            }

            is RequestMode.StartTargets -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val runConfiguration = resolveTargetsRunConfiguration(project, mode.targets)
                    ?: return createErrorResult(
                        "Could not create a run configuration for the specified targets (${mode.targets.size} classes). " +
                                "Provide fully qualified Java/Kotlin class names."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs,
                    includeSuccessOutput
                )
            }

            is RequestMode.StartPackage -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val runConfiguration = resolvePackageRunConfiguration(project, mode.packageName)
                    ?: return createErrorResult(
                        "Could not create a run configuration for package '${mode.packageName}'. " +
                                "Ensure the package exists and contains tests."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs,
                    includeSuccessOutput
                )
            }

            is RequestMode.StartDirectory -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val vFile = suspendingReadAction { resolveFile(project, mode.directory) }
                if (vFile == null || !vFile.isDirectory) {
                    return createErrorResult("Directory not found: '${mode.directory}'.")
                }

                val runConfiguration = resolveDirectoryRunConfiguration(project, mode.directory)
                    ?: return createErrorResult(
                        "Could not create a run configuration for directory '${mode.directory}'. " +
                                "Ensure the directory contains test sources."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs,
                    includeSuccessOutput
                )
            }

            is RequestMode.StartModule -> {
                val timeoutSeconds =
                    arguments[ParamNames.TIMEOUT_SECONDS]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_SECONDS
                if (timeoutSeconds <= 0) {
                    return createErrorResult("timeoutSeconds must be a positive integer.")
                }

                val module = ModuleManager.getInstance(project).findModuleByName(mode.module)
                if (module == null) {
                    return createErrorResult("Module not found: '${mode.module}'.")
                }

                val runConfiguration = resolveModuleRunConfiguration(project, mode.module)
                    ?: return createErrorResult(
                        "Could not create a run configuration for module '${mode.module}'. " +
                                "Ensure the module contains test sources."
                    )

                startRun(
                    project,
                    runConfiguration,
                    timeoutSeconds,
                    shouldActivateToolWindow(arguments),
                    waitSeconds,
                    callStartMs,
                    includeSuccessOutput
                )
            }
        }
    }

    /**
     * Registers the run in [ActiveTestRunRegistry] and only then launches the configuration:
     * the IDE's before-run tasks (compilation) can outlast any single call's wait budget
     * (issue #348), so the run must already be pollable by `runId` while the IDE is still
     * building — a call that runs out of budget before the process starts returns an
     * in-progress status, never an error. The registry owns the run's lifetime from
     * registration on: its watchdog bounds the starting phase by the start allowance and the
     * running phase by `timeoutSeconds` (anchored at process start, so build time is not
     * billed to the run), and the message-bus connection is disconnected when the run is
     * collected or evicted. This call only borrows the run to wait on it within its budget.
     */
    private suspend fun startRun(
        project: Project,
        runConfiguration: RunnerAndConfigurationSettings,
        timeoutSeconds: Int,
        activateToolWindow: Boolean,
        waitSeconds: Int,
        callStartMs: Long,
        includeSuccessOutput: Boolean = false
    ): CallToolResult {
        val configName = runConfiguration.name
        val executor = DefaultRunExecutor.getRunExecutorInstance()
        val env = ExecutionEnvironmentBuilder.createOrNull(executor, runConfiguration)
            ?.build(if (activateToolWindow) null else suppressToolWindowActivation())
            ?: return createErrorResult("Could not build execution environment for '$configName'.")

        val connection = project.messageBus.connect()
        val run = ActiveTestRunRegistry.ActiveTestRun(
            id = UUID.randomUUID().toString(),
            configName = configName,
            createdAtMs = System.currentTimeMillis(),
            timeoutSeconds = timeoutSeconds,
            processStartAllowanceMs = ActiveTestRunRegistry.processStartAllowanceMs(timeoutSeconds),
            exitCode = CompletableDeferred(),
            testRoot = CompletableDeferred(),
            connection = connection
        )
        connection.trackRunLifecycle(project, env, run)
        ActiveTestRunRegistry.getInstance(project).register(run)

        try {
            edtAction { ExecutionManager.getInstance(project).restartRunProfile(env) }
        } catch (t: Throwable) {
            // Nothing was launched; removing the run also disconnects the listener connection.
            ActiveTestRunRegistry.getInstance(project).remove(run.id)
            if (t is ProcessCanceledException || t is CancellationException || t !is Exception) throw t
            return createErrorResult(t.message ?: "Test process failed to start for '$configName'.", ToolNames.DIAGNOSTICS)
        }

        return awaitRunResult(project, run, waitSeconds, callStartMs, includeSuccessOutput)
    }

    /**
     * Waits for the run within what remains of this call's wait budget, then returns either the
     * final [RunTestsResult] (removing the run from the registry, unless it must stay armed —
     * see the removal guard below) or a [RunTestsInProgressResult] carrying the runId to poll
     * with.
     */
    private suspend fun awaitRunResult(
        project: Project,
        run: ActiveTestRunRegistry.ActiveTestRun,
        waitSeconds: Int,
        callStartMs: Long,
        includeSuccessOutput: Boolean = false
    ): CallToolResult {
        val exitCode: Int? = try {
            run.awaitWithinBudget(run.exitCode, waitSeconds, callStartMs)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // exitCode completes exceptionally only via markProcessNotStarted: the IDE reported
            // the process could never be started (before-run build failed or was cancelled).
            ActiveTestRunRegistry.getInstance(project).remove(run.id)
            return createErrorResult(
                e.message ?: "Test process failed to start for '${run.configName}'.",
                ToolNames.DIAGNOSTICS
            )
        }

        if (exitCode == null && !run.timedOutByWatchdog) {
            val processStartedAtMs = run.processStartedAtMs
            val elapsedSeconds = (System.currentTimeMillis() - (processStartedAtMs ?: run.createdAtMs)) / 1000
            return createJsonResult(
                buildInProgressResult(
                    run.id,
                    run.configName,
                    elapsedSeconds,
                    run.timeoutSeconds,
                    processStarted = processStartedAtMs != null
                )
            )
        }

        // Terminal: the process exited, or the watchdog killed it at timeoutSeconds (even if the
        // process has not confirmed its death yet).
        val smRoot = if (run.hasResultsViewer) {
            val finalizeMs = finalizeWaitMillis(waitSeconds, callStartMs, System.currentTimeMillis())
            withTimeoutOrNull(finalizeMs.milliseconds) { run.testRoot.await() }
        } else {
            null
        }
        if (smRoot == null) {
            LOG.debug("No SM test tree for '${run.configName}'; returning empty structured results.")
        }

        val outputs = smRoot?.let { collectRunOutputs(it, waitSeconds, callStartMs) }
        val tests = smRoot?.let {
            edtAction {
                TestResultsCollector.collectRunEntries(
                    it,
                    outputs = outputs?.perTest ?: emptyMap(),
                    includeSuccessOutput = includeSuccessOutput
                )
            }
        } ?: emptyList()
        val passed = tests.count { it.status == TestStatus.PASSED }
        val failed = tests.count { it.status == TestStatus.FAILED }
        val errors = tests.count { it.status == TestStatus.ERROR }

        // A run whose process never started (the start allowance expired mid-build) must stay
        // registered: its execution listener is the only guard that kills a process starting
        // after the timeout verdict, and removal would disconnect it. Retention eviction cleans
        // it up; if the process does start (and is killed), exitCode completes and the next
        // poll takes the removal path below.
        if (exitCode != null || run.processStartedAtMs != null) {
            ActiveTestRunRegistry.getInstance(project).remove(run.id)
        }

        val timedOut = run.timedOutByWatchdog
        val reportedExitCode = if (timedOut || exitCode == null) -1 else exitCode
        val runSucceeded = reportedExitCode == 0 && failed == 0 && errors == 0
        val runOutput = if (runSucceeded && !includeSuccessOutput) null else outputs?.unattributed
        return createJsonResult(
            RunTestsResult(
                success = runSucceeded,
                timedOut = timedOut,
                noTestsFound = tests.isEmpty() && reportedExitCode == 0,
                exitCode = reportedExitCode,
                passed = passed,
                failed = failed,
                errors = errors,
                total = tests.size,
                output = runOutput,
                tests = tests
            )
        )
    }

    /**
     * Collects console output from the finished SM tree (issue #346) on the platform's
     * "Tests Executor" — queued, never inline (`sync = false`), for two load-bearing reasons:
     * that sequential executor is where [CompositePrintable] flushes console chunks to disk, so
     * FIFO ordering guarantees every chunk the run flushed is on disk before the replay reads
     * it; and the executor thread is never the EDT, so the replay inside
     * [TestResultsCollector.collectRunOutputs] runs synchronously instead of being deferred.
     * Bounded by [outputWaitMillis]: on timeout or failure the output is dropped and the
     * structured results are still returned.
     */
    private suspend fun collectRunOutputs(
        root: SMTestProxy.SMRootTestProxy,
        waitSeconds: Int,
        callStartMs: Long
    ): TestResultsCollector.RunOutputs? {
        val collected = CompletableDeferred<TestResultsCollector.RunOutputs?>()
        CompositePrintable.invokeInAlarm({
            collected.complete(
                try {
                    TestResultsCollector.collectRunOutputs(root)
                } catch (_: ProcessCanceledException) {
                    null
                } catch (t: Throwable) {
                    LOG.warn("Failed to collect console output for test run", t)
                    null
                }
            )
        }, false)
        val waitMs = outputWaitMillis(waitSeconds, callStartMs, System.currentTimeMillis())
        return withTimeoutOrNull(waitMs.milliseconds) { collected.await() }
    }

    private suspend fun resolveRunConfiguration(project: Project, target: String): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)

        // Reuse an existing run configuration if the target names one directly.
        runManager.allSettings.find { it.name == target }?.let { return it }

        // Otherwise interpret the target as className (+ optional #method) and build a config from PSI.
        val (className, methodName) = parseTarget(target)

        requireSmartMode(project)

        val psiElement = suspendingReadAction {
            findClassByName(project, className)
                ?.let { if (methodName == null) it else findMethodElement(it, methodName) }
        } ?: return null

        return edtAction {
            val config = createConfigurationFromContext(project, psiElement) ?: return@edtAction null
            runManager.setTemporaryConfiguration(config)
            config
        }
    }

    private fun findMethodElement(psiClass: PsiElement, methodName: String): PsiMethod? =
        PsiUtils.resolveAsPsiClass(psiClass)?.methods?.firstOrNull { it.name == methodName }

    private fun createConfigurationFromContext(
        project: Project,
        psiElement: PsiElement
    ): RunnerAndConfigurationSettings? {
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(Location.DATA_KEY, PsiLocation.fromPsiElement(psiElement))
            .build()
        return ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
            .createConfigurationsFromContext()
            ?.firstOrNull()
            ?.configurationSettings
    }

    private fun createMultipleConfigurationsFromContext(
        project: Project,
        psiElements: List<PsiElement>
    ): RunnerAndConfigurationSettings? {
        val locations = psiElements.map { PsiLocation.fromPsiElement(it) }.toTypedArray()
        val dataContext = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(Location.DATA_KEY, locations.firstOrNull())
            .add(Location.DATA_KEYS, locations)
            .add(PlatformCoreDataKeys.PSI_ELEMENT_ARRAY, psiElements.toTypedArray())
            .build()
        return ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
            .createConfigurationsFromContext()
            ?.firstOrNull()
            ?.configurationSettings
    }

    internal suspend fun resolveTargetsRunConfiguration(
        project: Project,
        targets: List<String>
    ): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)
        val junitType = ConfigurationTypeUtil.findConfigurationType("JUnit")
        val factory = junitType?.configurationFactories?.firstOrNull()

        requireSmartMode(project)

        val psiElements = suspendingReadAction {
            targets.mapNotNull { target ->
                val (className, methodName) = parseTarget(target)
                findClassByName(project, className)?.let {
                    if (methodName == null) it else findMethodElement(it, methodName)
                }
            }
        }

        // Try ConfigurationContext with multiple locations first
        if (psiElements.isNotEmpty()) {
            val contextConfig = edtAction {
                createMultipleConfigurationsFromContext(project, psiElements)
            }
            if (contextConfig != null) {
                edtAction { runManager.setTemporaryConfiguration(contextConfig) }
                return contextConfig
            }
        }

        // Direct JUnit pattern configuration
        if (factory != null) {
            val name = if (targets.size == 1) targets[0] else "Tests in ${targets.take(2).joinToString(", ")}${if (targets.size > 2) " (+${targets.size - 2} more)" else ""}"
            return edtAction {
                try {
                    val settings = runManager.createConfiguration(name, factory)
                    val config = settings.configuration
                    val data = config.javaClass.getMethod("getPersistentData").invoke(config)
                    data.javaClass.getField("TEST_OBJECT").set(data, "pattern")

                    val patternSet = java.util.LinkedHashSet<String>()
                    for (t in targets) {
                        val (cls, method) = parseTarget(t)
                        if (method != null) {
                            patternSet.add("$cls,$method")
                        } else {
                            patternSet.add(cls)
                        }
                    }
                    val setPatternsMethod = data.javaClass.getMethod("setPatterns", java.util.LinkedHashSet::class.java)
                    setPatternsMethod.invoke(data, patternSet)

                    if (psiElements.isNotEmpty()) {
                        val firstFile = psiElements.first().containingFile?.virtualFile
                        if (firstFile != null) {
                            val module = ProjectFileIndex.getInstance(project).getModuleForFile(firstFile)
                            if (module != null) {
                                try {
                                    config.javaClass.getMethod("setModule", Module::class.java).invoke(config, module)
                                } catch (_: Exception) {}
                            }
                        }
                    }

                    runManager.setTemporaryConfiguration(settings)
                    settings
                } catch (e: Exception) {
                    LOG.warn("Failed to create pattern run configuration reflectively", e)
                    null
                }
            }
        }

        return null
    }

    internal suspend fun resolvePackageRunConfiguration(
        project: Project,
        packageName: String
    ): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)

        requireSmartMode(project)

        val psiPackage = suspendingReadAction {
            findPackageByName(project, packageName)
        }

        if (psiPackage != null) {
            val contextConfig = edtAction {
                createConfigurationFromContext(project, psiPackage)
            }
            if (contextConfig != null) {
                edtAction { runManager.setTemporaryConfiguration(contextConfig) }
                return contextConfig
            }
        }

        // Direct JUnit package configuration
        val junitType = ConfigurationTypeUtil.findConfigurationType("JUnit")
        val factory = junitType?.configurationFactories?.firstOrNull()
        if (factory != null) {
            return edtAction {
                try {
                    val settings = runManager.createConfiguration("All in $packageName", factory)
                    val config = settings.configuration
                    val data = config.javaClass.getMethod("getPersistentData").invoke(config)
                    data.javaClass.getField("TEST_OBJECT").set(data, "package")
                    data.javaClass.getField("PACKAGE_NAME").set(data, packageName)
                    val setScopeMethod = data.javaClass.getMethod("setScope", TestSearchScope::class.java)
                    setScopeMethod.invoke(data, TestSearchScope.WHOLE_PROJECT)
                    runManager.setTemporaryConfiguration(settings)
                    settings
                } catch (e: Exception) {
                    LOG.warn("Failed to create package run configuration reflectively", e)
                    null
                }
            }
        }

        return null
    }

    internal suspend fun resolveDirectoryRunConfiguration(
        project: Project,
        directory: String
    ): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)

        val vFile = suspendingReadAction { resolveFile(project, directory) }
            ?: return null
        if (!vFile.isDirectory) return null

        val psiDir = suspendingReadAction {
            PsiManager.getInstance(project).findDirectory(vFile)
        } ?: return null

        // Try ConfigurationContext first — works for Java, Python, JS, Go, etc.
        val contextConfig = edtAction {
            createConfigurationFromContext(project, psiDir)
        }
        if (contextConfig != null) {
            edtAction { runManager.setTemporaryConfiguration(contextConfig) }
            return contextConfig
        }

        // Direct JUnit directory configuration
        val junitType = ConfigurationTypeUtil.findConfigurationType("JUnit")
        val factory = junitType?.configurationFactories?.firstOrNull()
        if (factory != null) {
            return edtAction {
                try {
                    val settings = runManager.createConfiguration("All in ${vFile.name}", factory)
                    val config = settings.configuration
                    val data = config.javaClass.getMethod("getPersistentData").invoke(config)
                    data.javaClass.getField("TEST_OBJECT").set(data, "directory")
                    data.javaClass.getMethod("setDirName", String::class.java).invoke(data, vFile.path)
                    val module = ProjectFileIndex.getInstance(project).getModuleForFile(vFile)
                    if (module != null) {
                        try {
                            config.javaClass.getMethod("setModule", Module::class.java).invoke(config, module)
                            val setScopeMethod = data.javaClass.getMethod("setScope", TestSearchScope::class.java)
                            setScopeMethod.invoke(data, TestSearchScope.SINGLE_MODULE)
                        } catch (_: Exception) {}
                    }
                    runManager.setTemporaryConfiguration(settings)
                    settings
                } catch (e: Exception) {
                    LOG.warn("Failed to create directory run configuration reflectively", e)
                    null
                }
            }
        }

        return null
    }

    internal suspend fun resolveModuleRunConfiguration(
        project: Project,
        moduleName: String
    ): RunnerAndConfigurationSettings? {
        val runManager = RunManager.getInstance(project)
        val module = ModuleManager.getInstance(project).findModuleByName(moduleName)
            ?: return null

        // Direct JUnit module configuration (TEST_PACKAGE with empty package name in module scope)
        val junitType = ConfigurationTypeUtil.findConfigurationType("JUnit")
        val factory = junitType?.configurationFactories?.firstOrNull()
        if (factory != null) {
            val settings = edtAction {
                try {
                    val s = runManager.createConfiguration("All in $moduleName", factory)
                    val config = s.configuration
                    config.javaClass.getMethod("setModule", Module::class.java).invoke(config, module)
                    val data = config.javaClass.getMethod("getPersistentData").invoke(config)
                    data.javaClass.getField("TEST_OBJECT").set(data, "package")
                    data.javaClass.getField("PACKAGE_NAME").set(data, "")
                    val setScopeMethod = data.javaClass.getMethod("setScope", TestSearchScope::class.java)
                    setScopeMethod.invoke(data, TestSearchScope.SINGLE_MODULE)
                    runManager.setTemporaryConfiguration(s)
                    s
                } catch (e: Exception) {
                    LOG.warn("Failed to create module run configuration reflectively", e)
                    null
                }
            }
            if (settings != null) return settings
        }

        // Fallback for other languages: try ConfigurationContext on test/source roots of the module
        val testRoots = suspendingReadAction {
            val moduleRootManager = ModuleRootManager.getInstance(module)
            val fileIndex = ProjectFileIndex.getInstance(project)
            val sourceRoots = moduleRootManager.getSourceRoots(true).filter { fileIndex.isInTestSourceContent(it) }
            sourceRoots.ifEmpty { moduleRootManager.contentRoots.toList() }
        }
        for (root in testRoots) {
            val psiDir = suspendingReadAction { PsiManager.getInstance(project).findDirectory(root) } ?: continue
            val config = edtAction { createConfigurationFromContext(project, psiDir) }
            if (config != null) {
                edtAction { runManager.setTemporaryConfiguration(config) }
                return config
            }
        }

        return null
    }

    /**
     * Feeds the registry entry from the run's execution events. Subscribed before the profile
     * is launched, and writing straight into [run] rather than call-local state, because with
     * a slow before-run build every one of these events can fire after the starting call has
     * long returned an in-progress status (issue #348).
     */
    private fun MessageBusConnection.trackRunLifecycle(
        project: Project,
        env: ExecutionEnvironment,
        run: ActiveTestRunRegistry.ActiveTestRun
    ) {
        subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                if (environment !== env) return
                // Before startNotify, so an instantly-exiting process cannot slip its exit code past us.
                handler.addProcessListener(object : ProcessListener {
                    override fun processTerminated(event: ProcessEvent) {
                        run.exitCode.complete(event.exitCode)
                    }
                })
            }

            override fun processStarted(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                if (environment !== env) return
                // ExecutionManagerImpl registers the content descriptor before startNotify fires
                // this event, so the console (and its SM results viewer) is findable here. The
                // viewer listener must be attached before the run finishes — that is guaranteed
                // here, where the process has only just started.
                val descriptor = RunContentManager.getInstance(project).allDescriptors
                    .find { it.processHandler === handler }
                val resultsViewer = extractTestRunnerResultsViewer(descriptor?.executionConsole)
                resultsViewer?.addEventsListener(object : TestResultsViewer.EventsListener {
                    override fun onTestingFinished(sender: TestResultsViewer) {
                        run.testRoot.complete(sender.testsRootNode.root)
                    }
                })
                run.markProcessStarted(handler, hasResultsViewer = resultsViewer != null)
            }

            override fun processNotStarted(executorId: String, environment: ExecutionEnvironment) {
                if (environment !== env) return
                run.markProcessNotStarted(
                    "Test process failed to start for '${run.configName}' — the before-launch build " +
                            "failed or was cancelled."
                )
            }
        })
    }
}
