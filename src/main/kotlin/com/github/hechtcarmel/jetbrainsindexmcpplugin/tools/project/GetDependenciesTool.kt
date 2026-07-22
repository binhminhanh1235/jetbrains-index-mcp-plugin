package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.ProjectResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.models.ToolCallResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.DependenciesResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.LibraryDependency
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ModuleDependency
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class GetDependenciesTool : AbstractMcpTool() {
    override val requiresPsiSync: Boolean = false
    override val participatesInLifecycle: Boolean = true

    override val name = ToolNames.GET_DEPENDENCIES

    override val description = """
        Get project dependencies from the IDE's module model. 
        Provides module-level and library-level dependencies with scopes (COMPILE, TEST, RUNTIME, PROVIDED).
        Useful for understanding what external libraries or internal modules are available.
    """.trimIndent()

    override val inputSchema: JsonObject = SchemaBuilder.tool()
        .projectPath()
        .stringProperty("module", "Module name or path. If omitted, returns project-level summary.")
        .booleanProperty("includeTransitive", "Include transitive dependencies. Default: false.")
        .enumProperty("scope", "Scope filter. Default: all.", listOf("all", "compile", "test", "runtime"))
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): ToolCallResult {
        val moduleArg = arguments["module"]?.jsonPrimitive?.content
        val includeTransitive = arguments["includeTransitive"]?.jsonPrimitive?.booleanOrNull ?: false
        val scopeArg = arguments["scope"]?.jsonPrimitive?.content?.lowercase() ?: "all"

        val targetModule = if (moduleArg != null) {
            resolveTargetModule(project, moduleArg) ?: return createErrorResult("Module not found: $moduleArg")
        } else {
            // Pick the primary module if not specified, or just the first one if only one exists
            val modules = ModuleManager.getInstance(project).modules
            if (modules.isEmpty()) return createErrorResult("No modules found in project")
            // For now, if no module specified, just use the first module. 
            // In a real project, we might want to return an aggregate or require the module.
            // Let's use the first module that has the same name as the project, or just the first module.
            modules.firstOrNull { it.name == project.name } ?: modules.first()
        }

        val moduleDeps = mutableSetOf<ModuleDependency>()
        val libraryDeps = mutableSetOf<LibraryDependency>()
        val visitedModules = mutableSetOf<Module>()

        collectDependencies(targetModule, includeTransitive, scopeArg, moduleDeps, libraryDeps, visitedModules)

        return createJsonResult(
            DependenciesResult(
                module = targetModule.name,
                moduleDependencies = moduleDeps.toList().sortedBy { it.name },
                libraryDependencies = libraryDeps.toList().sortedBy { it.name },
                totalCount = moduleDeps.size + libraryDeps.size
            )
        )
    }

    private fun collectDependencies(
        module: Module,
        includeTransitive: Boolean,
        scopeFilter: String,
        moduleDeps: MutableSet<ModuleDependency>,
        libraryDeps: MutableSet<LibraryDependency>,
        visitedModules: MutableSet<Module>
    ) {
        if (!visitedModules.add(module)) return

        val rootManager = ModuleRootManager.getInstance(module)
        for (entry in rootManager.orderEntries) {
            val scopeName = if (entry is com.intellij.openapi.roots.ExportableOrderEntry) {
                entry.scope.name.uppercase()
            } else {
                "COMPILE"
            }

            if (!matchesScope(scopeFilter, scopeName)) continue

            val isExported = if (entry is com.intellij.openapi.roots.ExportableOrderEntry) entry.isExported else false

            when (entry) {
                is ModuleOrderEntry -> {
                    val depModule = entry.module
                    if (depModule != null) {
                        moduleDeps.add(ModuleDependency(depModule.name, scopeName, isExported))
                        if (includeTransitive) {
                            collectDependencies(depModule, true, scopeFilter, moduleDeps, libraryDeps, visitedModules)
                        }
                    } else {
                        moduleDeps.add(ModuleDependency(entry.moduleName, scopeName, isExported))
                    }
                }
                is LibraryOrderEntry -> {
                    val libName = entry.libraryName ?: entry.presentableName
                    
                    var groupId: String? = null
                    var artifactId: String? = null
                    var version: String? = null

                    // Parse Maven-style names: "Maven: org.springframework:spring-core:5.3.9" or "Gradle: org.springframework:spring-core:5.3.9"
                    val parts = libName.split(":")
                    if (parts.size >= 4 && (libName.startsWith("Maven: ") || libName.startsWith("Gradle: "))) {
                        groupId = parts[1].trim()
                        artifactId = parts[2].trim()
                        version = parts[3].trim()
                    }

                    libraryDeps.add(LibraryDependency(libName, groupId, artifactId, version, scopeName, isExported))
                }
            }
        }
    }

    private fun matchesScope(filter: String, actualScope: String): Boolean {
        if (filter == "all") return true
        return filter.equals(actualScope, ignoreCase = true)
    }

    private fun resolveTargetModule(project: Project, moduleNameOrPath: String): Module? {
        val modules = ModuleManager.getInstance(project).modules
        // 1. Try exact name match
        modules.firstOrNull { it.name == moduleNameOrPath }?.let { return it }
        
        // 2. Try path match
        val normalizedPath = ProjectResolver.normalizePath(moduleNameOrPath)
        for (module in modules) {
            val contentRoots = ModuleRootManager.getInstance(module).contentRoots
            for (root in contentRoots) {
                val rootPath = ProjectResolver.normalizePath(root.path)
                if (normalizedPath == rootPath || normalizedPath.startsWith("$rootPath/")) {
                    return module
                }
            }
        }
        return null
    }
}
