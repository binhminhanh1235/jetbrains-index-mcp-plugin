package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Batch diagnostics: run diagnostics on multiple files in a single MCP call.
 * Delegates to [GetDiagnosticsTool].
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
        .booleanProperty(ParamNames.INCLUDE_BUILD_ERRORS, "Include compiler build errors (default: false)")
        .booleanProperty(ParamNames.INCLUDE_TEST_RESULTS, "Include test failure results (default: false)")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val files = arguments[ParamNames.FILES]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return createErrorResult("Missing required parameter: ${ParamNames.FILES}")

        if (files.isEmpty()) {
            return createErrorResult("${ParamNames.FILES} array must not be empty")
        }

        val diagnosticsTool = GetDiagnosticsTool()
        return diagnosticsTool.execute(project, arguments)
    }
}
