package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class RunTestsTool : AbstractMcpTool() {
    companion object {
        private val LOG = logger<RunTestsTool>()
        private const val MAX_RAW_OUTPUT_CHARS = 100_000
    }

    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle: Boolean = true
    override val name = ToolNames.RUN_TESTS

    override val description = """
        Execute tests programmatically with structured pass/fail output.
        Target a specific file/line/column, or a fully qualified class name.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty(ParamNames.FILE, "File containing tests", required = false)
        .intProperty(ParamNames.LINE, "Target a specific test method by line", required = false)
        .intProperty(ParamNames.COLUMN, "Target a specific test method by column", required = false)
        .stringProperty("className", "FQN of test class (alternative to file/line)", required = false)
        .enumProperty("scope", "Execution scope. Default: auto-detect", listOf("file", "class", "method", "module", "all"), required = false)
        .booleanProperty("rerunFailed", "Re-run only last failed tests. Default: false", required = false)
        .booleanProperty("includeRawOutput", "Include raw stdout/stderr. Default: false", required = false)
        .intProperty("timeoutSeconds", "Must be positive. Default: 300 (5 min)", required = false)
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        if (!TrustedProjects.isProjectTrusted(project)) {
            return createErrorResult("Cannot run tests: project is not trusted. Open project settings to mark it as trusted.")
        }

        val className = optionalStringArg(arguments, "className")
        val includeRawOutput = arguments["includeRawOutput"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSeconds = arguments["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 300

        if (timeoutSeconds <= 0) {
            return createErrorResult("timeoutSeconds must be a positive integer.")
        }

        val targetElement = readAction {
            if (className != null) {
                findClassByName(project, className)
            } else {
                val file = optionalStringArg(arguments, ParamNames.FILE)
                if (file != null) {
                    val line = arguments[ParamNames.LINE]?.jsonPrimitive?.intOrNull ?: 1
                    val column = arguments[ParamNames.COLUMN]?.jsonPrimitive?.intOrNull ?: 1
                    findPsiElement(project, file, line, column) ?: getPsiFile(project, file)
                } else null
            }
        } ?: return createErrorResult("Could not resolve test target from arguments.")

        val startTime = System.currentTimeMillis()
        
        val deferred = CompletableDeferred<Unit>()
        val rawOutput = StringBuffer()

        val configAndSettings = readAction {
            val dataContext = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.PSI_ELEMENT, targetElement)
                .build()
            
            val context = ConfigurationContext.getFromContext(dataContext, ActionPlaces.UNKNOWN)
            context.configuration
        } ?: return createErrorResult("No test configuration producer found for the given target.")

        val environment = edtAction {
            val executor = DefaultRunExecutor.getRunExecutorInstance()
            val builder = ExecutionEnvironmentBuilder.create(executor, configAndSettings)
            val env = builder.build()
            env.callback = ProgramRunner.Callback { descriptor ->
                val processHandler = descriptor?.processHandler
                if (processHandler != null) {
                    if (processHandler.isProcessTerminated) {
                        deferred.complete(Unit)
                    } else {
                        processHandler.addProcessListener(object : ProcessAdapter() {
                            override fun processTerminated(event: ProcessEvent) {
                                deferred.complete(Unit)
                            }

                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                if (rawOutput.length < MAX_RAW_OUTPUT_CHARS) {
                                    rawOutput.append(event.text)
                                }
                            }
                        })
                    }
                } else {
                    deferred.complete(Unit)
                }
            }
            env
        }

        edtAction {
            ProgramRunnerUtil.executeConfiguration(environment, false, true)
        }

        val completed = withTimeoutOrNull(timeoutSeconds * 1000L) {
            deferred.await()
            true
        } ?: false

        val durationMs = System.currentTimeMillis() - startTime
        
        // Wait a tiny bit for test results tree to flush
        kotlinx.coroutines.delay(500)

        val results = edtAction {
            TestResultsCollector.collect(
                project = project,
                testResultFilter = "all",
                severity = "all",
                maxTestResults = 1000
            )
        }

        var finalResults = results?.testResults ?: emptyList()
        var finalSummary = results?.testSummary
        var truncated = results?.truncated ?: false
        
        if (finalSummary == null || finalSummary.total == 0) {
            val (parsedSummary, parsedResults) = TeamCityOutputParser.parse(rawOutput.toString())
            if (parsedSummary.total > 0) {
                finalSummary = parsedSummary
                finalResults = parsedResults
            }
        }

        if (finalSummary == null) {
            if (includeRawOutput && rawOutput.isNotEmpty()) {
                val response = RunTestsResult(
                    success = false,
                    timedOut = !completed,
                    testResults = emptyList(),
                    testSummary = com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestSummary(0, 0, 0, 0, null),
                    truncated = false,
                    rawOutput = rawOutput.toString(),
                    durationMs = durationMs
                )
                return createJsonResult(response)
            }
            return createErrorResult("Tests executed, but failed to collect structured results. Try with includeRawOutput=true.")
        }

        val success = finalSummary.failed == 0 && !completed.not()

        val response = RunTestsResult(
            success = success,
            timedOut = !completed,
            testResults = finalResults,
            testSummary = finalSummary,
            truncated = truncated,
            rawOutput = if (includeRawOutput) rawOutput.toString() else null,
            durationMs = durationMs
        )

        return createJsonResult(response)
    }
}
