package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence.DiagnosticsAnalysisService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RunTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.VerifyChangeResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class VerifyChangeTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false // We handle VFS sync manually
    override val participatesInLifecycle: Boolean = true

    override val name = ToolNames.VERIFY_CHANGE

    override val description = """
        Verify a code change by syncing the file system, checking for compilation/syntax errors, and optionally running nearby tests.
        Replaces calling ide_sync_files, ide_diagnostics, and ide_run_tests individually.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .file(required = true, description = "The file that was changed")
        .booleanProperty("runTests", "Also run nearby test files. Default: false")
        .intProperty("timeoutSeconds", "Total timeout in seconds. Default: 120")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        requireSmartMode(project)
        val startTime = System.currentTimeMillis()

        val filePath = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val runTests = arguments["runTests"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSeconds = arguments["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 120

        // 1. Sync
        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")
        
        edtAction {
            VfsUtil.markDirtyAndRefresh(false, true, true, virtualFile)
        }

        // 2. Diagnostics
        val analysisResult = DiagnosticsAnalysisService.getInstance(project).analyzeFile(
            virtualFile = virtualFile,
            filePath = filePath,
            severity = "errors",
            startLine = null,
            endLine = null,
            maxProblems = 100
        )
        val errors = analysisResult.problems
        val diagnosticsPass = errors.isNullOrEmpty()
        val errorCount = errors?.size ?: 0

        // 3. Tests
        var testsRun = false
        var testResult: RunTestsResult? = null

        if (runTests && diagnosticsPass) {
            val testFile = findNearbyTestFile(project, virtualFile)
            if (testFile != null) {
                testsRun = true
                
                // Construct args for RunTestsTool
                val testArgs = kotlinx.serialization.json.buildJsonObject {
                    put("file", kotlinx.serialization.json.JsonPrimitive(getRelativePath(project, testFile)))
                    put("timeoutSeconds", kotlinx.serialization.json.JsonPrimitive(timeoutSeconds))
                }
                
                try {
                    val runTestsToolResult = RunTestsTool().execute(project, testArgs)
                    if (runTestsToolResult.isError) {
                        // Just log or ignore, we will construct our own result
                    } else {
                        // Assuming tool returns JSON string in its content
                        val jsonStr = runTestsToolResult.content.toString() // We might need a better way to extract this
                        if (jsonStr.startsWith("{")) {
                            testResult = Json { ignoreUnknownKeys = true }.decodeFromString<RunTestsResult>(jsonStr)
                        }
                    }
                } catch (e: Exception) {
                    // Test execution failed
                }
            }
        }

        val success = diagnosticsPass && (!testsRun || (testResult?.success == true))
        val durationMs = System.currentTimeMillis() - startTime

        return createJsonResult(
            VerifyChangeResult(
                success = success,
                syncComplete = true,
                diagnosticsPass = diagnosticsPass,
                errorCount = errorCount,
                errors = errors,
                testsRun = testsRun,
                testSummary = testResult?.testSummary,
                testResults = testResult?.testResults,
                durationMs = durationMs
            )
        )
    }

    private fun findNearbyTestFile(project: Project, sourceFile: com.intellij.openapi.vfs.VirtualFile): com.intellij.openapi.vfs.VirtualFile? {
        val basename = sourceFile.nameWithoutExtension
        val possibleNames = listOf("${basename}Test", "Test${basename}", "${basename}Tests", "${basename}Spec")
        
        var foundFile: com.intellij.openapi.vfs.VirtualFile? = null
        val searchScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        
        com.intellij.openapi.application.ReadAction.run<Exception> {
            val fileIndex = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).fileIndex
            val psiManager = com.intellij.psi.PsiManager.getInstance(project)
            
            com.intellij.psi.search.FilenameIndex.getAllFilesByExt(project, sourceFile.extension ?: "", searchScope).forEach { file ->
                if (fileIndex.isInTestSourceContent(file)) {
                    val nameWithoutExt = file.nameWithoutExtension
                    if (possibleNames.contains(nameWithoutExt)) {
                        foundFile = file
                        return@run
                    }
                }
            }
        }
        
        return foundFile
    }
}
