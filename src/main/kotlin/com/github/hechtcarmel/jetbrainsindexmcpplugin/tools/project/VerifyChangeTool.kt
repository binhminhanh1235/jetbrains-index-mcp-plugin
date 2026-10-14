package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }

    override val requiresPsiSync: Boolean = false // We handle VFS sync manually
    override val participatesInLifecycle: Boolean = true

    override val name = ToolNames.VERIFY_CHANGE

    override val description = """
        Verify a code change by syncing the file system, checking for compilation/syntax errors, and optionally running nearby tests.
        Replaces calling ide_sync_files, ide_diagnostics, and ide_run_tests individually.
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .file(required = true, description = "The file that was changed")
        .booleanProperty("runTests", "Also run nearby test files. Default: false")
        .intProperty("timeoutSeconds", "Total timeout in seconds. Default: 120")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        requireSmartMode(project)
        val startTime = System.currentTimeMillis()

        val filePath = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val runTests = arguments["runTests"]?.jsonPrimitive?.booleanOrNull ?: false
        val timeoutSeconds = arguments["timeoutSeconds"]?.jsonPrimitive?.intOrNull ?: 120

        // 1. Sync
        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")
        
        VfsUtil.markDirtyAndRefresh(false, true, true, virtualFile)
        commitDocuments(project)

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
                
                val testTarget = com.intellij.openapi.application.ReadAction.compute<String, Exception> {
                    val psiFile = com.intellij.psi.PsiManager.getInstance(project).findFile(testFile)
                    (psiFile as? com.intellij.psi.PsiClassOwner)?.classes?.firstOrNull()?.qualifiedName
                } ?: getRelativePath(project, testFile)

                // Construct args for RunTestsTool
                val testArgs = kotlinx.serialization.json.buildJsonObject {
                    put("target", kotlinx.serialization.json.JsonPrimitive(testTarget))
                    put("timeoutSeconds", kotlinx.serialization.json.JsonPrimitive(timeoutSeconds))
                }
                
                try {
                    val runTestsToolResult = RunTestsTool().execute(project, testArgs)
                    if (runTestsToolResult.isError == true) {
                        // Just log or ignore, we will construct our own result
                    } else {
                        val jsonStr = (runTestsToolResult.content.firstOrNull() as? TextContent)?.text ?: ""
                        if (jsonStr.startsWith("{")) {
                            testResult = json.decodeFromString<RunTestsResult>(jsonStr)
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
                testRun = testResult,
                durationMs = durationMs
            )
        )
    }

    private fun findNearbyTestFile(project: Project, sourceFile: com.intellij.openapi.vfs.VirtualFile): com.intellij.openapi.vfs.VirtualFile? {
        val basename = sourceFile.nameWithoutExtension
        val ext = sourceFile.extension?.let { ".$it" } ?: ""
        val candidateNames = listOf(
            "${basename}Test$ext",
            "Test${basename}$ext",
            "${basename}Tests$ext",
            "${basename}Spec$ext",
            "test_${basename}$ext",
            "${basename}.test$ext",
            "${basename}.spec$ext"
        )
        
        var foundFile: com.intellij.openapi.vfs.VirtualFile? = null
        val searchScope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
        
        com.intellij.openapi.application.ReadAction.run<Exception> {
            val fileIndex = com.intellij.openapi.roots.ProjectRootManager.getInstance(project).fileIndex
            for (candidate in candidateNames) {
                val files = com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(candidate, searchScope)
                val testCandidate = files.firstOrNull { fileIndex.isInTestSourceContent(it) }
                    ?: files.firstOrNull { it.path.contains("/test") || it.path.contains("/tests") }
                    ?: files.firstOrNull()
                if (testCandidate != null) {
                    foundFile = testCandidate
                    return@run
                }
            }
        }
        
        return foundFile
    }
}
