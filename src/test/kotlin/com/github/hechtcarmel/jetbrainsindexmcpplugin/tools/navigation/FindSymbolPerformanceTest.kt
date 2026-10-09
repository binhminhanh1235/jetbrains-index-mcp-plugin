package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.OptimizedSymbolSearch
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis

class FindSymbolPerformanceTest : McpPlatformTestCase() {

    fun testFindSymbolPerformance() = runBlocking {
        registerSourceRoot("src")
        for (i in 1..2000) {
            val className = "PerformanceTestClass$i"
            val source = """
                package perf;
                public class $className {
                    public void commonMethodName() {}
                    public void anotherCommonMethodName() {}
                    public void duplicateName() {}
                    public void duplicateName() {}
                }
            """.trimIndent()
            writeProjectFile("src/perf/$className.java", source)
        }

        // Add lots of duplicate method names
        for (i in 1..50) {
            val source = """
                package perf2;
                public class DuplicateClass$i {
                    public void commonMethodName() {}
                }
            """.trimIndent()
            writeProjectFile("src/perf2/DuplicateClass$i.java", source)
        }

        val scope = GlobalSearchScope.projectScope(project)

        // Warm up
        OptimizedSymbolSearch.search(project, "commonMethodName", scope, 10)

        val time = measureTimeMillis {
            val results = OptimizedSymbolSearch.search(project, "commonMethodName", scope, 5000)
            println("Found ${results.size} results")
        }

        println("Search took $time ms")

        val time2 = measureTimeMillis {
            val results = OptimizedSymbolSearch.search(project, "common", scope, 5000)
            println("Found ${results.size} results")
        }
        println("Search 2 took $time2 ms")
    }
}
