package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.ReferencesSearch

class SafeDeleteToolBenchmarkTest : McpPlatformTestCase() {

    @Test
    fun testPerformanceOfLayer3() {
        val DECLARATIONS = 20000

        val sb = StringBuilder()
        for (i in 1..DECLARATIONS) {
            sb.append("fun myFunction$i() {}\n")
        }
        writeProjectFile("src/File1.kt", sb.toString())
        writeProjectFile("src/File2.kt", "fun use() { myFunction${DECLARATIONS}() }")

        registerSourceRoot("src")

        val tool = SafeDeleteTool()
        val args = buildJsonObject {
            put("target_type", "file")
            put("file", "src/File1.kt")
            put("force", false)
            put("dryRun", true)
        }

        // Warmup
        runBlocking { tool.execute(project, args) }

        val time = measureTimeMillis {
            runBlocking {
                val result = tool.execute(project, args)
                println("Result: ${toolText(result).take(100)}")
            }
        }

        println("BENCHMARK TIME: $time ms")
    }
}
