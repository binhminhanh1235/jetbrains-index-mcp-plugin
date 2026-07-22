package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.EntryPoint
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ModuleOverview
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ProjectOverviewResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.json.JsonObject
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.io.File

class GetProjectOverviewTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle: Boolean = true

    override val name = ToolNames.GET_PROJECT_OVERVIEW

    override val description = """
        Get a structured overview of the project architecture.
        Returns modules, languages, frameworks, build systems, top-level packages, and entry points.
        Useful when first exploring a new codebase.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val moduleManager = ModuleManager.getInstance(project)
        val modules = moduleManager.modules

        val moduleOverviews = mutableListOf<ModuleOverview>()
        val languages = mutableSetOf<String>()
        val frameworks = mutableSetOf<String>()
        val testFrameworks = mutableSetOf<String>()
        val topPackages = mutableSetOf<String>()
        val entryPoints = mutableListOf<EntryPoint>()

        var buildSystem: String? = null
        if (project.basePath != null) {
            val baseDir = File(project.basePath!!)
            if (File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()) {
                buildSystem = "Gradle"
            } else if (File(baseDir, "pom.xml").exists()) {
                buildSystem = "Maven"
            }
            
            // Simple entry point check for package.json
            val packageJson = File(baseDir, "package.json")
            if (packageJson.exists()) {
                entryPoints.add(EntryPoint(packageJson.path, "package_json"))
            }
        }

        for (module in modules) {
            val rootManager = ModuleRootManager.getInstance(module)
            
            val prodRoots = rootManager.getSourceRoots(false)
            val testRoots = rootManager.getSourceRoots(true).filter { !prodRoots.contains(it) }
            
            moduleOverviews.add(ModuleOverview(module.name, prodRoots.size, testRoots.size))

            // Scan files for languages and top packages
            for (root in prodRoots) {
                collectLanguagesAndPackages(root, languages, topPackages, root)
            }

            // Framework detection via dependencies
            for (entry in rootManager.orderEntries) {
                if (entry is LibraryOrderEntry) {
                    val libName = entry.libraryName ?: entry.presentableName
                    val lower = libName.lowercase()
                    
                    if (lower.contains("org.springframework")) frameworks.add("Spring")
                    if (lower.contains("io.ktor")) frameworks.add("Ktor")
                    if (lower.contains("django")) frameworks.add("Django")
                    if (lower.contains("flask")) frameworks.add("Flask")
                    if (lower.contains("react")) frameworks.add("React")
                    if (lower.contains("next")) frameworks.add("Next.js")
                    if (lower.contains("express")) frameworks.add("Express")

                    if (lower.contains("junit")) testFrameworks.add("JUnit")
                    if (lower.contains("testng")) testFrameworks.add("TestNG")
                    if (lower.contains("pytest")) testFrameworks.add("PyTest")
                }
            }
        }

        return createJsonResult(
            ProjectOverviewResult(
                name = project.name,
                basePath = project.basePath ?: "",
                moduleCount = modules.size,
                modules = moduleOverviews,
                languages = languages.toList().sorted(),
                frameworks = frameworks.toList().sorted(),
                buildSystem = buildSystem,
                topLevelPackages = topPackages.toList().sorted(),
                entryPoints = entryPoints.take(10),
                testFrameworks = testFrameworks.toList().sorted()
            )
        )
    }

    private fun collectLanguagesAndPackages(
        dir: VirtualFile,
        languages: MutableSet<String>,
        topPackages: MutableSet<String>,
        sourceRoot: VirtualFile,
        depth: Int = 0
    ) {
        if (depth > 2) return
        
        var hasFiles = false
        var childDirs = 0
        
        for (child in dir.children) {
            if (child.isDirectory) {
                childDirs++
                collectLanguagesAndPackages(child, languages, topPackages, sourceRoot, depth + 1)
            } else {
                hasFiles = true
                when (child.extension?.lowercase()) {
                    "kt", "kts" -> languages.add("Kotlin")
                    "java" -> languages.add("Java")
                    "py" -> languages.add("Python")
                    "js", "jsx" -> languages.add("JavaScript")
                    "ts", "tsx" -> languages.add("TypeScript")
                    "go" -> languages.add("Go")
                    "rb" -> languages.add("Ruby")
                    "rs" -> languages.add("Rust")
                    "cpp", "c", "h", "hpp" -> languages.add("C/C++")
                }
            }
        }
        
        if (hasFiles && depth > 0) {
            val relativePath = dir.path.removePrefix(sourceRoot.path)
            val pkg = relativePath.trim('/').replace('/', '.')
            if (pkg.isNotEmpty()) {
                topPackages.add(pkg)
            }
        }
    }
}
