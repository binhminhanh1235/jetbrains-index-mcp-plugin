package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path

/**
 * Platform tier tests verifying test configuration resolution for scoped execution:
 * - targets (batch multi-class/method)
 * - package scope
 * - directory scope
 * - module scope
 * and validating scope error boundaries.
 */
class RunTestsScopeBehaviorTest : McpPlatformTestCase() {

    private companion object {
        const val TEST_SOURCES = "src/test/java"
    }

    private val tool = RunTestsTool()

    override fun setUp() {
        super.setUp()
        addSourceRoot(TEST_SOURCES, isTestSource = true)
        writeProjectFile(
            "$TEST_SOURCES/org/junit/Test.java", """
            package org.junit;

            public @interface Test {
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/sample/AlphaTest.java", """
            package sample;

            import org.junit.Test;

            public class AlphaTest {
                @Test
                public void testAlpha() {}
            }
            """.trimIndent()
        )
        writeProjectFile(
            "$TEST_SOURCES/sample/BetaTest.java", """
            package sample;

            import org.junit.Test;

            public class BetaTest {
                @Test
                public void testBeta() {}
            }
            """.trimIndent()
        )
    }

    private fun addSourceRoot(relativePath: String, isTestSource: Boolean) {
        val basePath = requireNotNull(project.basePath) { "Project base path is null" }
        val path = Path.of(basePath, relativePath)
        Files.createDirectories(path)
        val root = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByPath(path.toString())) {
            "Failed to refresh VFS for source root $path"
        }
        if (ModuleRootManager.getInstance(module).sourceRoots.none { it.path == root.path }) {
            PsiTestUtil.addSourceRoot(module, root, isTestSource)
        }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    fun testResolveTargetsRunConfigurationMultipleClasses() = runBlocking {
        val settings = tool.resolveTargetsRunConfiguration(
            project,
            listOf("sample.AlphaTest", "sample.BetaTest")
        )
        assertNotNull("Should create run configuration for multiple targets", settings)
        val configName = settings!!.name
        assertTrue(
            "Configuration name should mention targets: $configName",
            configName.contains("AlphaTest") && configName.contains("BetaTest")
        )
    }

    fun testResolveTargetsRunConfigurationSingleTarget() = runBlocking {
        val settings = tool.resolveTargetsRunConfiguration(
            project,
            listOf("sample.AlphaTest#testAlpha")
        )
        assertNotNull("Should create run configuration for single target", settings)
        assertTrue(
            "Configuration name should mention test method: ${settings!!.name}",
            settings.name.contains("testAlpha")
        )
    }

    fun testResolvePackageRunConfiguration() = runBlocking {
        val settings = tool.resolvePackageRunConfiguration(project, "sample")
        assertNotNull("Should create run configuration for package", settings)
        assertTrue(
            "Configuration name should mention package: ${settings!!.name}",
            settings.name.contains("sample")
        )
    }

    fun testResolveDirectoryRunConfiguration() = runBlocking {
        val settings = tool.resolveDirectoryRunConfiguration(project, "$TEST_SOURCES/sample")
        assertNotNull("Should create run configuration for directory", settings)
        assertTrue(
            "Configuration name should mention directory name: ${settings!!.name}",
            settings.name.contains("sample")
        )
    }

    fun testResolveModuleRunConfiguration() = runBlocking {
        val settings = tool.resolveModuleRunConfiguration(project, module.name)
        assertNotNull("Should create run configuration for module", settings)
        assertTrue(
            "Configuration name should mention module name: ${settings!!.name}",
            settings.name.contains(module.name)
        )
    }

    fun testExecuteNonExistentDirectoryFailsGracefully() = runBlocking {
        val result = tool.execute(project, buildJsonObject {
            put("directory", "non/existent/test/path")
        })
        assertToolFailed("Non-existent directory must fail", result)
        assertTrue(
            "Error message should mention directory not found: ${toolText(result)}",
            toolText(result).contains("Directory not found")
        )
    }

    fun testExecuteNonExistentModuleFailsGracefully() = runBlocking {
        val result = tool.execute(project, buildJsonObject {
            put("module", "non_existent_module_xyz")
        })
        assertToolFailed("Non-existent module must fail", result)
        assertTrue(
            "Error message should mention module not found: ${toolText(result)}",
            toolText(result).contains("Module not found")
        )
    }

    fun testExecuteNegativeTimeoutFails() = runBlocking {
        val result = tool.execute(project, buildJsonObject {
            put("package", "sample")
            put("timeoutSeconds", -5)
        })
        assertToolFailed("Negative timeout must fail", result)
        assertTrue(
            "Error message should mention timeoutSeconds: ${toolText(result)}",
            toolText(result).contains("timeoutSeconds must be a positive integer")
        )
    }

    fun testExecuteMultipleSelectorsFails() = runBlocking {
        val result = tool.execute(project, buildJsonObject {
            put("package", "sample")
            put("directory", "$TEST_SOURCES/sample")
        })
        assertToolFailed("Multiple selectors must fail", result)
        assertTrue(
            "Error message should mention mutually exclusive selectors: ${toolText(result)}",
            toolText(result).contains("not multiple")
        )
    }

    fun testExecuteEmptyTargetsFails() = runBlocking {
        val result = tool.execute(project, buildJsonObject {
            putJsonArray("targets") {}
        })
        assertToolFailed("Empty targets array must fail", result)
        assertTrue(
            "Error message should mention targets array empty: ${toolText(result)}",
            toolText(result).contains("targets array must not be empty")
        )
    }
}
