package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Batch import optimization: optimize imports in multiple files in one MCP call.
 *
 * Replaces: ide_optimize_imports × N calls with a single round-trip.
 */
class BatchOptimizeImportsTool : AbstractMcpTool() {

    companion object {
        private val LOG = logger<BatchOptimizeImportsTool>()
    }

    override val name = ToolNames.BATCH_OPTIMIZE_IMPORTS

    override val description = """
        Optimize imports in multiple files with a single call. Removes unused imports and organizes remaining imports per project style.

        Equivalent to calling ide_optimize_imports for each file, but in one round-trip.

        Parameters:
        - files (required): Array of file paths to optimize imports for

        Returns: per-file success/failure summary.

        Example: {"files": ["src/MyController.java", "src/MyService.java"]}
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .property(
            ParamNames.FILES,
            buildJsonObject {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put("description", "Array of file paths to optimize imports for")
            },
            required = true
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val files = arguments[ParamNames.FILES]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: return createErrorResult("Missing required parameter: ${ParamNames.FILES}")

        if (files.isEmpty()) {
            return createErrorResult("${ParamNames.FILES} array must not be empty")
        }

        val results = mutableMapOf<String, String>()  // file -> "ok" or "error: ..."

        for (file in files) {
            val psiFile = suspendingReadAction {
                val virtualFile = resolveFile(project, file) ?: return@suspendingReadAction null
                PsiManager.getInstance(project).findFile(virtualFile)
            }

            if (psiFile == null) {
                results[file] = "error: file not found"
                continue
            }

            var errorMessage: String? = null
            edtAction {
                try {
                    OptimizeImportsProcessor(project, psiFile).runWithoutProgress()
                } catch (e: Exception) {
                    LOG.warn("Optimize imports failed for $file", e)
                    errorMessage = e.message ?: "unknown error"
                }
            }

            results[file] = errorMessage?.let { "error: $it" } ?: "ok"
        }

        // Save all documents once after all files processed
        edtAction { FileDocumentManager.getInstance().saveAllDocuments() }
        commitDocuments(project)

        val successCount = results.values.count { it == "ok" }
        val failureCount = results.size - successCount

        val resultsArray = buildJsonArray {
            results.forEach { (file, status) ->
                add(buildJsonObject {
                    put("file", file)
                    put("status", status)
                })
            }
        }

        val summary = buildJsonObject {
            put("filesProcessed", files.size)
            put("successCount", successCount)
            put("failureCount", failureCount)
            put("results", resultsArray)
        }

        return createSuccessResult(summary.toString())
    }
}
