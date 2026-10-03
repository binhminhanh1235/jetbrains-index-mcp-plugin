package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DiagnosticsResult
import com.intellij.codeInsight.CodeSmellInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DiagnosticsWhitespacePathBehaviorTest : McpPlatformTestCase() {
    private val decoder = Json { ignoreUnknownKeys = true }

    fun testSingleFilePreservesLeadingWhitespaceInDirectoryName() = runBlocking {
        val whitespaceRoot = registerSourceRoot(" source")
        val regularRoot = registerSourceRoot("source")
        writeProjectFile(" source/Target.java", "class IntendedTarget {}")
        writeProjectFile("source/Target.java", "class DifferentTarget {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val previous = service.closedFileAnalysisOverride
        try {
            service.closedFileAnalysisOverride = { request ->
                listOf(CodeSmellInfo(request.document, request.document.text, TextRange(0, 1), HighlightSeverity.ERROR))
            }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("file", " source/Target.java")
                put("severity", "errors")
            })
            assertToolSucceeded("An existing project-relative path must resolve verbatim", result)
            val diagnostics = decoder.decodeFromString<DiagnosticsResult>(toolText(result))
            assertEquals("Must analyze the requested file, not the trimmed sibling", "class IntendedTarget {}", diagnostics.problems.orEmpty().single().message)
            assertEquals(" source/Target.java", diagnostics.problems.orEmpty().single().file)
        } finally {
            service.closedFileAnalysisOverride = previous
            removeSourceRoot(whitespaceRoot)
            removeSourceRoot(regularRoot)
        }
    }

    fun testBatchKeepsDistinctFilesWhoseNamesDifferByLeadingWhitespace() = runBlocking {
        val whitespaceRoot = registerSourceRoot(" source")
        val regularRoot = registerSourceRoot("source")
        writeProjectFile(" source/Target.java", "class IntendedTarget {}")
        writeProjectFile("source/Target.java", "class DifferentTarget {}")
        val service = DiagnosticsAnalysisService.getInstance(project)
        val previous = service.closedFileAnalysisOverride
        try {
            service.closedFileAnalysisOverride = { request ->
                listOf(CodeSmellInfo(request.document, request.document.text, TextRange(0, 1), HighlightSeverity.ERROR))
            }
            val result = GetDiagnosticsTool().execute(project, buildJsonObject {
                put("files", buildJsonArray {
                    add(JsonPrimitive(" source/Target.java"))
                    add(JsonPrimitive("source/Target.java"))
                })
                put("severity", "errors")
            })
            assertToolSucceeded("The two distinct files are both valid batch targets", result)
            val diagnostics = decoder.decodeFromString<DiagnosticsResult>(toolText(result))
            assertEquals("Different physical files must not be deduplicated", 2, diagnostics.fileAnalyses.orEmpty().size)
            assertEquals(setOf("class IntendedTarget {}", "class DifferentTarget {}"), diagnostics.problems.orEmpty().map { it.message }.toSet())
        } finally {
            service.closedFileAnalysisOverride = previous
            removeSourceRoot(whitespaceRoot)
            removeSourceRoot(regularRoot)
        }
    }

    private fun removeSourceRoot(root: VirtualFile) {
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.contentEntries.forEach { entry ->
                entry.sourceFolders.filter { it.url == root.url }.forEach(entry::removeSourceFolder)
            }
        }
    }
}
