package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Batch diagnostics: run diagnostics on multiple files in a single MCP call.
 *
 * Note: [VerifyChangeTool] syncs VFS + analyzes one file. This tool analyzes
 * multiple files without syncing — use after ide_sync_files or ide_verify_change
 * when VFS is already fresh.
 */
class BatchDiagnosticsTool : AbstractMcpTool() {
    override val name = ToolNames.BATCH_DIAGNOSTICS

    override val description = """
        Run diagnostics on multiple files in a single call. Returns errors and warnings for all specified files.
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .property(
            ParamNames.FILES,
            buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Array of file paths to run diagnostics on")
            },
            required = true
        )
        .enumProperty(ParamNames.SEVERITY, "Severity filter", listOf("errors", "warnings", "all"))
        .intProperty("maxProblems", "Maximum total problems to collect across all files (default: 200, max: 500).", required = false)
        .booleanProperty(ParamNames.INCLUDE_BUILD_ERRORS, "Include compiler build errors (default: true)")
        .booleanProperty(ParamNames.INCLUDE_TEST_RESULTS, "Include test failure results (default: false)")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val files = arguments[ParamNames.FILES]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return createErrorResult("Missing required parameter: ${ParamNames.FILES}")

        if (files.isEmpty()) {
            return createErrorResult("${ParamNames.FILES} array must not be empty")
        }

        requireSmartMode(project)

        val severity = arguments[ParamNames.SEVERITY]?.jsonPrimitive?.contentOrNull ?: "errors"
        val includeBuildErrors = arguments[ParamNames.INCLUDE_BUILD_ERRORS]?.jsonPrimitive?.booleanOrNull ?: true
        val includeTestResults = arguments[ParamNames.INCLUDE_TEST_RESULTS]?.jsonPrimitive?.booleanOrNull ?: false
        val maxProblems = arguments["maxProblems"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 500) ?: 200

        var hasErrors = false
        var totalProblems = 0
        var problemsTruncated = false
        val resultsArray = buildJsonArray {
            for (file in files) {
                if (totalProblems >= maxProblems) {
                    problemsTruncated = true
                    break
                }
                val virtualFile = resolveFile(project, file)
                if (virtualFile == null) {
                    add(buildJsonObject {
                        put("file", file)
                        put("error", "file not found")
                        put("problemCount", 0)
                    })
                    continue
                }

                val remainingCap = maxProblems - totalProblems
                val analysisResult = DiagnosticsAnalysisService.getInstance(project).analyzeFile(
                    virtualFile = virtualFile,
                    filePath = file,
                    severity = severity,
                    startLine = null,
                    endLine = null,
                    maxProblems = remainingCap
                )
                val problems = analysisResult.problems
                totalProblems += problems.size
                if (totalProblems >= maxProblems) {
                    problemsTruncated = true
                }
                if (problems.isNotEmpty()) hasErrors = true

                add(buildJsonObject {
                    put("file", file)
                    put("problemCount", problems.size)
                    put("analysisFresh", analysisResult.analysisFresh)
                    put("analysisTimedOut", analysisResult.analysisTimedOut)
                    analysisResult.analysisMessage?.let { put("analysisMessage", it) }
                    if (problems.isNotEmpty()) {
                        putJsonArray("problems") {
                            problems.forEach { problem ->
                                add(buildJsonObject {
                                    put("message", problem.message)
                                    put("severity", problem.severity)
                                    put("line", problem.line)
                                    put("column", problem.column)
                                })
                            }
                        }
                    }
                })
            }
        }

        val summary = buildJsonObject {
            put("filesChecked", files.size)
            put("hasErrors", hasErrors)
            put("totalProblems", totalProblems)
            if (problemsTruncated) {
                put("problemsTruncated", true)
            }
            put("results", resultsArray)
        }

        return createSuccessResult(summary.toString())
    }
}
