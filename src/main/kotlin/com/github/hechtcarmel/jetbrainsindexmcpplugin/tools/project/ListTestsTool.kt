package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ListTestsResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestEntry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.editor.Document
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testIntegration.TestFramework
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ListTestsTool : AbstractMcpTool() {

    companion object {
        internal const val MAX_TESTS = 500

        internal fun globToRegex(glob: String): Regex {
            val regexStr = buildString {
                for (char in glob) {
                    when (char) {
                        '*' -> append(".*")
                        '?' -> append(".")
                        '.' -> append("\\.")
                        '\\' -> append("\\\\")
                        '$', '^', '(', ')', '[', ']', '{', '}', '+', '|' -> append("\\$char")
                        else -> append(char)
                    }
                }
            }
            return Regex("^$regexStr$", RegexOption.IGNORE_CASE)
        }
    }

    override val name = ToolNames.LIST_TESTS

    override val description = """
        List all test methods discovered by the IDE's test framework extension points (JUnit, TestNG, etc.).

        Supports filtering by file, package, directory, module, class name glob pattern, and framework,
        as well as pagination via maxResults and offset.

        Returns: list of test entries with className, methodName, file path, and line number.
        The className is fully qualified when the language exposes it (e.g. Java, Kotlin, PHP), so it
        can be passed directly to ide_run_tests.
        Note: requires smart mode (IDE indexing must be complete).
        
        Parameters:
        - project_path (optional): required when multiple projects are open.
        - file (optional): if given, lists only tests in that file; otherwise scans test sources.
        - package (optional): filter tests by fully qualified package name (e.g. 'com.example.service').
        - directory (optional): filter tests by directory path relative to project root (e.g. 'src/test/kotlin').
        - module (optional): filter tests by IntelliJ module name.
        - classPattern (optional): glob pattern to match class names (e.g. '*ServiceTest').
        - framework (optional): filter tests by framework name (e.g. 'JUnit4', 'JUnit5').
        - maxResults (optional, default $MAX_TESTS): maximum number of test entries to return.
        - offset (optional, default 0): starting offset for pagination.

        Example: {} or {"package": "com.example.service"} or {"classPattern": "*Test", "maxResults": 50}
    """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .file(
            required = false,
            description = "Path to a specific test file relative to project root. If omitted, lists all tests in the project (or within the specified scope)."
        )
        .stringProperty(
            ParamNames.PACKAGE,
            "Filter tests by fully qualified package name (e.g. 'com.example.service')."
        )
        .stringProperty(
            ParamNames.DIRECTORY,
            "Filter tests by directory path relative to project root (e.g. 'src/test/kotlin')."
        )
        .stringProperty(
            ParamNames.MODULE,
            "Filter tests by IntelliJ module name."
        )
        .stringProperty(
            ParamNames.CLASS_PATTERN,
            "Filter tests by class name pattern / glob (e.g. '*ServiceTest')."
        )
        .stringProperty(
            ParamNames.FRAMEWORK,
            "Filter tests by framework name (e.g. 'JUnit4', 'JUnit5')."
        )
        .intProperty(
            ParamNames.MAX_RESULTS,
            "Maximum number of test entries to return. Default: $MAX_TESTS."
        )
        .intProperty(
            ParamNames.OFFSET,
            "Starting offset for pagination. Default: 0."
        )
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        requireSmartMode(project)

        val frameworks = TestFramework.EXTENSION_NAME.extensionList
        if (frameworks.isEmpty()) {
            return createErrorResult("No test frameworks are registered in this IDE session.")
        }

        val filePath = optionalStringArg(arguments, ParamNames.FILE)
        val packageFilter = optionalStringArg(arguments, ParamNames.PACKAGE)
        val directoryFilter = optionalStringArg(arguments, ParamNames.DIRECTORY)
        val moduleFilter = optionalStringArg(arguments, ParamNames.MODULE)
        val classPatternFilter = optionalStringArg(arguments, ParamNames.CLASS_PATTERN)
        val frameworkFilter = optionalStringArg(arguments, ParamNames.FRAMEWORK)
        val maxResults = arguments[ParamNames.MAX_RESULTS]?.jsonPrimitive?.intOrNull ?: MAX_TESTS
        val offset = arguments[ParamNames.OFFSET]?.jsonPrimitive?.intOrNull ?: 0

        if (maxResults <= 0) {
            return createErrorResult("maxResults must be a positive integer.")
        }
        if (offset < 0) {
            return createErrorResult("offset must be non-negative.")
        }
        if (filePath != null && resolveFile(project, filePath) == null) {
            return createErrorResult("File not found: '$filePath'.")
        }
        val directoryVf = if (directoryFilter != null) {
            val dir = resolveFile(project, directoryFilter)
            if (dir == null || !dir.isDirectory) {
                return createErrorResult("Directory not found: '$directoryFilter'.")
            }
            dir
        } else {
            null
        }
        if (moduleFilter != null) {
            val module = ModuleManager.getInstance(project).findModuleByName(moduleFilter)
            if (module == null) {
                return createErrorResult("Module not found: '$moduleFilter'.")
            }
        }

        val effectiveFrameworks = if (frameworkFilter != null) {
            frameworks.filter { it.name.equals(frameworkFilter, ignoreCase = true) }
        } else {
            frameworks
        }
        if (effectiveFrameworks.isEmpty() && frameworkFilter != null) {
            return createJsonResult(
                ListTestsResult(tests = emptyList(), count = 0, truncated = false)
            )
        }

        val classPatternRegex = classPatternFilter?.let { globToRegex(it) }

        val limit = offset + maxResults
        val tests = suspendingReadAction {
            collectTests(
                project = project,
                frameworks = effectiveFrameworks,
                filePath = filePath,
                packageFilter = packageFilter,
                directoryVf = directoryVf,
                moduleFilter = moduleFilter,
                classPatternRegex = classPatternRegex,
                limit = limit
            )
        }

        val truncated = tests.size > limit
        val page = tests.drop(offset).take(maxResults)
        return createJsonResult(
            ListTestsResult(tests = page, count = page.size, truncated = truncated)
        )
    }

    /**
     * Walks the requested test sources and returns discovered test methods matching all filters,
     * collecting up to [limit] + 1 entries to detect truncation.
     */
    private fun collectTests(
        project: Project,
        frameworks: List<TestFramework>,
        filePath: String?,
        packageFilter: String?,
        directoryVf: VirtualFile?,
        moduleFilter: String?,
        classPatternRegex: Regex?,
        limit: Int
    ): List<TestEntry> {
        val docManager = PsiDocumentManager.getInstance(project)
        val results = mutableListOf<TestEntry>()

        fun scan(psiFile: PsiFile) {
            val document = docManager.getDocument(psiFile) ?: return
            val relativePath = getRelativePath(project, psiFile.virtualFile)

            psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    checkCanceled()
                    val entry = toTestEntry(element, frameworks, psiFile, document, relativePath)
                    if (entry != null) {
                        if (packageFilter != null) {
                            val matchesPackage = entry.className.startsWith("$packageFilter.") ||
                                    entry.className.substringBeforeLast('.', "") == packageFilter ||
                                    (psiFile as? PsiClassOwner)?.packageName?.let {
                                        it == packageFilter || it.startsWith("$packageFilter.")
                                    } == true
                            if (!matchesPackage) return
                        }
                        if (classPatternRegex != null) {
                            val simpleName = entry.className.substringAfterLast('.')
                            if (!classPatternRegex.matches(simpleName) && !classPatternRegex.matches(entry.className)) {
                                return
                            }
                        }
                        results.add(entry)
                        if (results.size > limit) {
                            stopWalking()
                            return
                        }
                    }
                    super.visitElement(element)
                }
            })
        }

        if (filePath != null) {
            getPsiFile(project, filePath)?.let(::scan)
        } else {
            val psiManager = PsiManager.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            fileIndex.iterateContent { vf ->
                if (!vf.isDirectory && fileIndex.isInTestSourceContent(vf)) {
                    if (moduleFilter != null && fileIndex.getModuleForFile(vf)?.name != moduleFilter) {
                        return@iterateContent true
                    }
                    if (directoryVf != null && !VfsUtilCore.isAncestor(directoryVf, vf, false)) {
                        return@iterateContent true
                    }
                    psiManager.findFile(vf)?.let(::scan)
                }
                results.size <= limit
            }
        }

        return results
    }

    /**
     * Builds a [TestEntry] if [element] is a test method for any registered framework, else null.
     */
    private fun toTestEntry(
        element: PsiElement,
        frameworks: List<TestFramework>,
        psiFile: PsiFile,
        document: Document,
        relativePath: String
    ): TestEntry? {
        val framework = frameworks.firstOrNull { fw ->
            try { fw.isTestMethod(element) }
            catch (e: ProcessCanceledException) { throw e }
            catch (e: Exception) { false }
        } ?: return null
        val methodName = (element as? PsiNamedElement)?.name ?: return null
        val className = findContainingClassName(element) ?: psiFile.name.substringBeforeLast('.')
        val line = document.getLineNumber(element.textOffset) + 1

        return TestEntry(
            framework = framework.name,
            className = className,
            methodName = methodName,
            displayName = "$className.$methodName",
            file = relativePath,
            line = line
        )
    }

    /**
     * Name of the nearest enclosing named element (the test's class), fully qualified when the
     * language's PSI exposes one (see [PsiUtils.qualifiedName]); otherwise the simple name.
     */
    private fun findContainingClassName(element: PsiElement): String? {
        val owner = PsiUtils.findNamedElement(element.parent) ?: return null
        return PsiUtils.qualifiedName(owner) ?: owner.name
    }
}

