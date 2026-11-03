package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.python

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.rethrowIfControlFlow
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.*
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.toArgumentFailure
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureKind
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.StructureNode
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.intellij.util.Query
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CancellationException

/**
 * Registration entry point for Python language handlers.
 *
 * This class is loaded via reflection when the Python plugin is available.
 * It registers all Python-specific handlers with the [LanguageHandlerRegistry].
 *
 * ## Python PSI Classes Used (via reflection)
 *
 * - `com.jetbrains.python.psi.PyClass` - Python class declarations
 * - `com.jetbrains.python.psi.PyFunction` - Python function/method declarations
 * - `com.jetbrains.python.psi.PyCallExpression` - Function/method calls
 * - `com.jetbrains.python.psi.stubs.PyClassNameIndex` - Index for finding classes by name
 * - `com.jetbrains.python.psi.stubs.PyFunctionNameIndex` - Index for finding functions by name
 * - `com.jetbrains.python.psi.search.PyClassInheritorsSearch` - Search for subclasses
 * - `com.jetbrains.python.psi.search.PyOverridingMethodsSearch` - Search for overriding methods
 */
object PythonHandlers {

    private val LOG = logger<PythonHandlers>()

    /**
     * Registers all Python handlers with the registry.
     *
     * Called via reflection from [LanguageHandlerRegistry].
     */
    @JvmStatic
    fun register(registry: LanguageHandlerRegistry) {
        if (!PluginDetectors.python.isAvailable) {
            LOG.info("Python plugin not available, skipping Python handler registration")
            return
        }

        try {
            // Verify Python classes are accessible before registering
            Class.forName("com.jetbrains.python.psi.PyClass")
            Class.forName("com.jetbrains.python.psi.PyFunction")

            registry.registerTypeHierarchyHandler(PythonTypeHierarchyHandler())
            registry.registerImplementationsHandler(PythonImplementationsHandler())
            registry.registerCallHierarchyHandler(PythonCallHierarchyHandler())
            registry.registerSuperMethodsHandler(PythonSuperMethodsHandler())
            registry.registerStructureHandler(PythonStructureHandler())
            registry.registerSymbolReferenceHandler(PythonSymbolReferenceHandler())

            LOG.info("Registered Python handlers")
        } catch (e: ClassNotFoundException) {
            LOG.warn("Python PSI classes not found, skipping registration: ${e.message}")
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            LOG.warn("Failed to register Python handlers: ${e.message}")
        }
    }
}

/**
 * Base class for Python handlers with common utilities.
 *
 * Uses reflection to access Python PSI classes to avoid compile-time dependencies.
 */
abstract class BasePythonHandler<T> : LanguageHandler<T> {

    /**
     * Checks if the element is from a Python language.
     */
    protected fun isPythonLanguage(element: PsiElement): Boolean {
        return element.language.id == "Python"
    }

    protected val pyClassClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyClass")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val pyFunctionClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyFunction")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val pyCallExpressionClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.PyCallExpression")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected val pyTypeEvalContextClass: Class<*>? by lazy {
        try {
            Class.forName("com.jetbrains.python.psi.types.TypeEvalContext")
        } catch (e: ClassNotFoundException) {
            null
        }
    }

    protected fun getRelativePath(project: Project, file: com.intellij.openapi.vfs.VirtualFile): String {
        return ProjectUtils.getToolFilePath(project, file)
    }

    protected fun getLineNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        return document.getLineNumber(element.textOffset) + 1
    }

    protected fun getColumnNumber(project: Project, element: PsiElement): Int? {
        val psiFile = element.containingFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null
        val lineNumber = document.getLineNumber(element.textOffset)
        return element.textOffset - document.getLineStartOffset(lineNumber) + 1
    }

    /**
     * Checks if element is a PyClass using reflection.
     */
    protected fun isPyClass(element: PsiElement): Boolean {
        return pyClassClass?.isInstance(element) == true
    }

    /**
     * Checks if element is a PyFunction using reflection.
     */
    protected fun isPyFunction(element: PsiElement): Boolean {
        return pyFunctionClass?.isInstance(element) == true
    }

    /**
     * Finds containing PyClass using reflection.
     */
    protected fun findContainingPyClass(element: PsiElement): PsiElement? {
        if (isPyClass(element)) return element
        val pyClass = pyClassClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, pyClass as Class<out PsiElement>)
    }

    /**
     * Finds containing PyFunction using reflection.
     */
    protected fun findContainingPyFunction(element: PsiElement): PsiElement? {
        if (isPyFunction(element)) return element
        val pyFunction = pyFunctionClass ?: return null
        @Suppress("UNCHECKED_CAST")
        return PsiTreeUtil.getParentOfType(element, pyFunction as Class<out PsiElement>)
    }

    /**
     * Gets the name of a PyClass or PyFunction via reflection.
     */
    protected fun getName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }
    }

    /**
     * Gets the qualified name of a PyClass via reflection.
     */
    protected fun getQualifiedName(element: PsiElement): String? {
        return try {
            val method = element.javaClass.getMethod("getQualifiedName")
            method.invoke(element) as? String
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }
    }

    /**
     * Gets superclasses of a PyClass via reflection.
     */
    protected fun getSuperClasses(
        pyClass: PsiElement,
        context: Any? = createCodeAnalysisContext(pyClass.project, pyClass.containingFile)
    ): Array<*>? {
        val typeEvalContextClass = pyTypeEvalContextClass ?: return null
        return try {
            val method = pyClass.javaClass.getMethod("getSuperClasses", typeEvalContextClass)
            method.invoke(pyClass, context) as? Array<*>
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }
    }

    /**
     * Finds a method by name in a PyClass via reflection.
     */
    protected fun findMethodInClass(
        pyClass: PsiElement,
        methodName: String,
        context: Any? = createUserInitiatedContext(pyClass.project, pyClass.containingFile)
    ): PsiElement? {
        val typeEvalContextClass = pyTypeEvalContextClass

        if (typeEvalContextClass != null) {
            try {
                val method = pyClass.javaClass.getMethod(
                    "findMethodByName",
                    String::class.java,
                    java.lang.Boolean.TYPE,
                    typeEvalContextClass
                )
                val result = method.invoke(pyClass, methodName, false, context) as? PsiElement
                if (result != null) {
                    return result
                }
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                // Fall back to enumerating methods below.
            }
        }

        return try {
            val getMethodsMethod = pyClass.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(pyClass) as? Array<*> ?: return null
            methods.filterIsInstance<PsiElement>().find { getName(it) == methodName }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }
    }

    private fun createCodeAnalysisContext(project: Project, origin: PsiFile?): Any? {
        return createTypeEvalContext("codeAnalysis", project, origin)
    }

    private fun createUserInitiatedContext(project: Project, origin: PsiFile?): Any? {
        return createTypeEvalContext("userInitiated", project, origin)
    }

    private fun createTypeEvalContext(factoryMethod: String, project: Project, origin: PsiFile?): Any? {
        val typeEvalContextClass = pyTypeEvalContextClass ?: return null

        return try {
            val method = typeEvalContextClass.getMethod(factoryMethod, Project::class.java, PsiFile::class.java)
            method.invoke(null, project, origin)
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            try {
                val fallbackMethod = typeEvalContextClass.getMethod("codeInsightFallback", Project::class.java)
                fallbackMethod.invoke(null, project)
            } catch (failure: Exception) {
                failure.rethrowIfControlFlow()
                null
            }
        }
    }
}

/**
 * Python implementation of [TypeHierarchyHandler].
 */
class PythonTypeHierarchyHandler : BasePythonHandler<TypeHierarchyData>(), TypeHierarchyHandler {

    companion object {
        private const val MAX_HIERARCHY_DEPTH = 50
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyClassClass != null

    override fun getTypeHierarchy(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        directOnly: Boolean,
        direction: TypeHierarchyDirection?,
        page: HierarchyPageRequest?
    ): TypeHierarchyData? {
        require(page == null || direction != null) { "Hierarchy pagination requires an explicit direction" }
        val pyClass = findContainingPyClass(element) ?: return null
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val collectionLimit = page?.collectionLimit ?: 100
        val rawSupertypes = if (direction != TypeHierarchyDirection.SUBTYPE) {
            getSupertypes(project, pyClass, searchScope = searchScope, directOnly = directOnly).take(page?.collectionLimit ?: Int.MAX_VALUE)
        } else emptyList()
        val rawSubtypes = if (direction != TypeHierarchyDirection.SUPERTYPE) {
            getSubtypes(project, pyClass, searchScope, directOnly, collectionLimit)
        } else emptyList()
        val (supertypes, superNext) = if (direction == TypeHierarchyDirection.SUPERTYPE) {
            rawSupertypes.applyHierarchyPage(page)
        } else rawSupertypes to null
        val (subtypes, subtypeNext) = if (direction == TypeHierarchyDirection.SUBTYPE) {
            rawSubtypes.applyHierarchyPage(page)
        } else rawSubtypes to null

        return TypeHierarchyData(
            element = TypeElementData(
                name = getQualifiedName(pyClass) ?: getName(pyClass) ?: "unknown",
                qualifiedName = getQualifiedName(pyClass),
                file = pyClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                line = getLineNumber(project, pyClass),
                kind = "CLASS",
                language = "Python",
                pointerTarget = pyClass
            ),
            supertypes = supertypes,
            subtypes = subtypes,
            nextOffset = superNext ?: subtypeNext
        )
    }

    private fun getSupertypes(
        project: Project,
        pyClass: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 0,
        searchScope: GlobalSearchScope,
        directOnly: Boolean = false
    ): List<TypeElementData> {
        if (depth > MAX_HIERARCHY_DEPTH) return emptyList()

        val className = getQualifiedName(pyClass) ?: getName(pyClass) ?: return emptyList()
        if (className in visited || className == "object") return emptyList()
        visited.add(className)

        val supertypes = mutableListOf<TypeElementData>()

        try {
            val superClasses = getSuperClasses(pyClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superName = getQualifiedName(superClass) ?: getName(superClass)
                if (
                    superName != null &&
                    superName != "object" &&
                    shouldIncludeNavigationElement(searchScope, superClass)
                ) {
                    val superSupertypes = if (directOnly) emptyList() else
                        getSupertypes(project, superClass, visited, depth + 1, searchScope, directOnly = false)
                    supertypes.add(TypeElementData(
                        name = superName,
                        qualifiedName = getQualifiedName(superClass),
                        file = superClass.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superClass),
                        kind = "CLASS",
                        language = "Python",
                        supertypes = superSupertypes.takeIf { it.isNotEmpty() },
                        pointerTarget = superClass
                    ))
                }
            }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            // Handle gracefully
        }

        return supertypes
    }

    private fun getSubtypes(
        project: Project,
        pyClass: PsiElement,
        searchScope: GlobalSearchScope,
        directOnly: Boolean = false,
        maxResults: Int = 100
    ): List<TypeElementData> {
        val includeCandidate: (PsiElement) -> Boolean = { candidate ->
            !directOnly || isVisibleSubtypeOf(candidate, pyClass, searchScope) { parent ->
                getSuperClasses(parent)?.filterIsInstance<PsiElement>().orEmpty()
            }
        }
        val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
        val searchMethod = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE)
        fun collect(deep: Boolean): List<TypeElementData> = collectSubtypes(
            project, searchScope, maxResults, includeCandidate
        ) { searchMethod.invoke(null, pyClass, deep) as? Query<*> }

        // A shallow query is sufficient for the common directOnly case. If excluded
        // intermediates hide visible leaves, supplement it with a deep query for bridging.
        if (!directOnly) return collect(deep = true)
        val shallow = collect(deep = false)
        if (shallow.size >= maxResults) return shallow
        val seen = shallow.mapTo(mutableSetOf()) { it.pointerTarget }
        return shallow + collect(deep = true).filter { seen.add(it.pointerTarget) }.take(maxResults - shallow.size)
    }

    /** Collect a plugin query without turning an interrupted partial level into a complete one. */
    internal fun collectSubtypes(
        project: Project,
        searchScope: GlobalSearchScope,
        maxResults: Int,
        includeCandidate: (PsiElement) -> Boolean = { true },
        search: () -> Query<*>?
    ): List<TypeElementData> {
        if (maxResults <= 0) return emptyList()

        return try {
            val inheritors = search() ?: return emptyList()
            val results = mutableListOf<TypeElementData>()
            inheritors.forEach(Processor { candidate ->
                val inheritor = candidate as? PsiElement
                if (inheritor != null && shouldIncludeNavigationElement(searchScope, inheritor) && includeCandidate(inheritor)) {
                    results.add(TypeElementData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        qualifiedName = getQualifiedName(inheritor),
                        file = inheritor.containingFile?.virtualFile?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, inheritor),
                        kind = "CLASS",
                        language = "Python",
                        pointerTarget = inheritor
                    ))
                }
                results.size < maxResults
            })
            results
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            emptyList()
        }
    }
}

/**
 * Python implementation of [ImplementationsHandler].
 */
class PythonImplementationsHandler : BasePythonHandler<List<ImplementationData>>(), ImplementationsHandler {

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyClassClass != null

    override fun findImplementations(
        element: PsiElement,
        project: Project,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean
    ): List<ImplementationData>? {
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)
        val pyFunction = findContainingPyFunction(element)
        if (pyFunction != null) {
            return findMethodImplementations(project, pyFunction, searchScope)
        }

        val pyClass = findContainingPyClass(element)
        if (pyClass != null) {
            return findClassImplementations(project, pyClass, searchScope)
        }

        return null
    }

    private fun findMethodImplementations(
        project: Project,
        pyFunction: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyOverridingMethodsSearch")
            val searchMethod = searchClass.getMethod("search", pyFunctionClass, java.lang.Boolean.TYPE)
            val query = searchMethod.invoke(null, pyFunction, true)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val overridingMethods = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            overridingMethods.filterIsInstance<PsiElement>()
                .filter { shouldIncludeNavigationElement(searchScope, it) }
                .take(MAX_COLLECTED_NAVIGATION_RESULTS)
                .mapNotNull { overridingMethod ->
                    val file = overridingMethod.containingFile?.virtualFile ?: return@mapNotNull null
                    val containingClass = findContainingPyClass(overridingMethod)
                    val className = containingClass?.let { getName(it) } ?: ""
                    val methodName = getName(overridingMethod) ?: "unknown"
                    ImplementationData(
                        name = if (className.isNotEmpty()) "$className.$methodName" else methodName,
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, overridingMethod) ?: 0,
                        column = getColumnNumber(project, overridingMethod) ?: 0,
                        kind = "METHOD",
                        language = "Python",
                        pointerTarget = overridingMethod
                    )
                }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            emptyList()
        }
    }

    private fun findClassImplementations(
        project: Project,
        pyClass: PsiElement,
        searchScope: GlobalSearchScope
    ): List<ImplementationData> {
        return try {
            val searchClass = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            val searchMethod = searchClass.getMethod("search", pyClassClass, java.lang.Boolean.TYPE)
            val query = searchMethod.invoke(null, pyClass, true)

            val findAllMethod = query.javaClass.getMethod("findAll")
            val inheritors = findAllMethod.invoke(query) as? Collection<*> ?: return emptyList()

            inheritors.filterIsInstance<PsiElement>()
                .filter { shouldIncludeNavigationElement(searchScope, it) }
                .take(MAX_COLLECTED_NAVIGATION_RESULTS)
                .mapNotNull { inheritor ->
                    val file = inheritor.containingFile?.virtualFile ?: return@mapNotNull null
                    ImplementationData(
                        name = getQualifiedName(inheritor) ?: getName(inheritor) ?: "unknown",
                        file = getRelativePath(project, file),
                        line = getLineNumber(project, inheritor) ?: 0,
                        column = getColumnNumber(project, inheritor) ?: 0,
                        kind = "CLASS",
                        language = "Python",
                        pointerTarget = inheritor
                    )
                }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            emptyList()
        }
    }
}

/**
 * Python implementation of [CallHierarchyHandler].
 */
class PythonCallHierarchyHandler : BasePythonHandler<CallHierarchyData>(), CallHierarchyHandler {

    companion object {
        private const val MAX_RESULTS_PER_LEVEL = 20
        private const val MAX_STACK_DEPTH = 50
        private const val MAX_SUPER_METHODS = 10
        private val LOG = logger<PythonCallHierarchyHandler>()
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyFunctionClass != null

    override fun getCallHierarchy(
        element: PsiElement,
        project: Project,
        direction: String,
        depth: Int,
        scope: BuiltInSearchScope,
        excludeGenerated: Boolean,
        page: HierarchyPageRequest?
    ): CallHierarchyData? {
        val pyFunction = findContainingPyFunction(element) ?: return null
        val visited = mutableSetOf<String>()
        val searchScope = createNavigationSearchScope(project, scope, excludeGenerated)

        val maxResults = page?.collectionLimit ?: MAX_RESULTS_PER_LEVEL
        val rawCalls = if (direction == "callers") {
            findCallersRecursive(project, pyFunction, depth, visited, searchScope = searchScope, maxResults = maxResults)
        } else {
            findCalleesRecursive(project, pyFunction, depth, visited, searchScope = searchScope, maxResults = maxResults)
        }
        val (calls, nextOffset) = rawCalls.applyHierarchyPage(page)

        return CallHierarchyData(
            element = createCallElement(project, pyFunction),
            calls = calls,
            nextOffset = nextOffset
        )
    }

    /**
     * Finds all super methods that the given method overrides.
     * This is used to also search for callers of base methods, since those
     * calls could be dispatched to this method at runtime (polymorphism).
     */
    private fun findAllSuperMethods(project: Project, pyFunction: PsiElement): Set<PsiElement> {
        val superMethods = mutableSetOf<PsiElement>()
        val visited = mutableSetOf<String>()
        findSuperMethodsRecursive(project, pyFunction, superMethods, visited)
        return superMethods.take(MAX_SUPER_METHODS).toSet()
    }

    private fun findSuperMethodsRecursive(
        project: Project,
        pyFunction: PsiElement,
        result: MutableSet<PsiElement>,
        visited: MutableSet<String>
    ) {
        val containingClass = findContainingPyClass(pyFunction) ?: return
        val methodName = getName(pyFunction) ?: return

        val superClasses = getSuperClasses(containingClass)
        superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
            val superClassName = getQualifiedName(superClass) ?: getName(superClass)
            val key = "$superClassName.$methodName"
            if (key in visited) return@forEach
            visited.add(key)

            val superMethod = findMethodInClass(superClass, methodName)
            if (superMethod != null) {
                result.add(superMethod)
                findSuperMethodsRecursive(project, superMethod, result, visited)
            }
        }
    }

    private fun findCallersRecursive(
        project: Project,
        pyFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope,
        maxResults: Int
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()
        if (maxResults <= 0) return emptyList()

        val functionKey = getFunctionKey(pyFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        return collectIncludedCallers(
            project, findDirectCallers(pyFunction), depth, visited, stackDepth, searchScope, maxResults
        )
    }

    /** Scope filtering and semantic counting are independent of the optional native API lookup. */
    internal fun collectIncludedCallers(
        project: Project,
        directCallers: Sequence<PsiElement>,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int,
        searchScope: GlobalSearchScope,
        maxResults: Int
    ): List<CallElementData> {
        val callers = mutableListOf<CallElementData>()
        val includedKeys = mutableSetOf<String>()
        // The native API already returns a map. Consume it lazily until enough unique included
        // callers exist; an arbitrary raw prefix cannot prove that the filtered search is over.
        for (directCaller in directCallers) {
            ProgressManager.checkCanceled()
            if (callers.size >= maxResults) break

            val children = if (depth > 1) {
                findCallersRecursive(
                    project, directCaller, depth - 1, visited, stackDepth + 1, searchScope, maxResults
                )
            } else null

            val candidates = if (shouldIncludeNavigationElement(searchScope, directCaller)) {
                listOf(createCallElement(project, directCaller, children))
            } else {
                children.orEmpty()
            }
            for (candidate in candidates) {
                if (callers.size >= maxResults) break
                val key = candidate.pointerTarget?.let(::getFunctionKey)
                    ?: "${candidate.file}|${candidate.line}|${candidate.column}|${candidate.name}"
                if (includedKeys.add(key)) callers.add(candidate)
            }
        }

        return callers
    }

    private fun findDirectCallers(pyFunction: PsiElement): Sequence<PsiElement> {
        return findCallersUsingPyStaticHierarchy(pyFunction)
    }

    /**
     * Mirrors PyCharm's own Python caller hierarchy implementation when the API is available.
     * This uses Python-specific find-usages semantics rather than generic ReferencesSearch.
     */
    private fun findCallersUsingPyStaticHierarchy(pyFunction: PsiElement): Sequence<PsiElement> {
        return try {
            val pyElementClass = Class.forName("com.jetbrains.python.psi.PyElement")
            val hierarchyUtilClass = Class.forName("com.jetbrains.python.hierarchy.call.PyStaticCallHierarchyUtil")
            val getCallersMethod = hierarchyUtilClass.getMethod("getCallers", pyElementClass)

            val callers = getCallersMethod.invoke(null, pyFunction) as? Map<*, *> ?: return emptySequence()
            callers.keys.asSequence()
                .onEach { ProgressManager.checkCanceled() }
                .filterIsInstance<PsiElement>()
                .mapNotNull { caller ->
                    when {
                        isPyFunction(caller) -> caller
                        else -> findContainingPyFunction(caller)
                    }
                }
                .filter { it != pyFunction }
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "Python caller hierarchy requires PyCharm's call hierarchy API, but it is unavailable in this IDE/Python plugin build.",
                e
            )
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException(
                "Python caller hierarchy requires PyCharm's call hierarchy API signature, but the current IDE/Python plugin build is incompatible.",
                e
            )
        } catch (e: LinkageError) {
            LOG.warn("Python call hierarchy API linkage failed", e)
            throw IllegalStateException(
                "Python caller hierarchy is unavailable because the IDE/Python plugin API is incompatible with this plugin build.",
                e
            )
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            val cause = (e as? InvocationTargetException)?.targetException ?: e
            when (cause) {
                is ProcessCanceledException -> throw cause
                is CancellationException -> throw cause
                is IndexNotReadyException -> throw cause
            }
            LOG.warn("Python call hierarchy API failed", e)
            throw IllegalStateException(
                "Python caller hierarchy failed inside the IDE's Python call hierarchy API.",
                e
            )
        }
    }

    private fun findCalleesRecursive(
        project: Project,
        pyFunction: PsiElement,
        depth: Int,
        visited: MutableSet<String>,
        stackDepth: Int = 0,
        searchScope: GlobalSearchScope,
        maxResults: Int
    ): List<CallElementData> {
        if (stackDepth > MAX_STACK_DEPTH || depth <= 0) return emptyList()
        if (maxResults <= 0) return emptyList()

        val functionKey = getFunctionKey(pyFunction)
        if (functionKey in visited) return emptyList()
        visited.add(functionKey)

        val callees = mutableListOf<CallElementData>()
        val includedKeys = mutableSetOf<String>()
        try {
            val pyCallExpr = pyCallExpressionClass ?: return emptyList()
            PsiTreeUtil.processElements(pyFunction) { candidate ->
                if (pyCallExpr.isInstance(candidate)) {
                    val calledFunction = resolveCallExpression(candidate)
                    if (calledFunction != null && isPyFunction(calledFunction)) {
                        val children = if (depth > 1) {
                            findCalleesRecursive(
                                project,
                                calledFunction,
                                depth - 1,
                                visited,
                                stackDepth + 1,
                                searchScope,
                                maxResults
                            )
                        } else null
                        val candidates = if (shouldIncludeNavigationElement(searchScope, calledFunction)) {
                            listOf(createCallElement(project, calledFunction, children))
                        } else {
                            children.orEmpty()
                        }
                        for (callee in candidates) {
                            if (callees.size >= maxResults) break
                            val key = callee.pointerTarget?.let(::getFunctionKey)
                                ?: "${callee.file}|${callee.line}|${callee.column}|${callee.name}"
                            if (includedKeys.add(key)) {
                                callees.add(callee)
                            }
                        }
                    }
                }
                callees.size < maxResults
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            // Handle gracefully
        }
        return callees
    }

    private fun resolveCallExpression(callExpr: PsiElement): PsiElement? {
        return try {
            // Get the callee and resolve it
            val calleeMethod = callExpr.javaClass.getMethod("getCallee")
            val callee = calleeMethod.invoke(callExpr) as? PsiElement ?: return null

            val referenceMethod = callee.javaClass.getMethod("getReference")
            val reference = referenceMethod.invoke(callee) as? com.intellij.psi.PsiReference
            reference?.resolve()
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }
    }

    private fun getFunctionKey(pyFunction: PsiElement): String {
        val file = pyFunction.containingFile?.virtualFile?.url
        if (file != null) return "$file|${pyFunction.textOffset}"
        val containingClass = findContainingPyClass(pyFunction)
        val className = containingClass?.let { getQualifiedName(it) ?: getName(it) } ?: ""
        val functionName = getName(pyFunction) ?: ""
        return "$className.$functionName|${pyFunction.textOffset}"
    }

    private fun createCallElement(project: Project, pyFunction: PsiElement, children: List<CallElementData>? = null): CallElementData {
        val file = pyFunction.containingFile?.virtualFile
        val containingClass = findContainingPyClass(pyFunction)
        val className = containingClass?.let { getName(it) }
        val functionName = getName(pyFunction) ?: "unknown"

        val name = if (className != null) "$className.$functionName" else functionName

        return CallElementData(
            name = name,
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, pyFunction) ?: 0,
            column = getColumnNumber(project, pyFunction) ?: 0,
            language = "Python",
            children = children?.takeIf { it.isNotEmpty() },
            pointerTarget = pyFunction
        )
    }
}

/**
 * Python implementation of [SuperMethodsHandler].
 */
class PythonSuperMethodsHandler : BasePythonHandler<SuperMethodsData>(), SuperMethodsHandler {

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyFunctionClass != null

    override fun findSuperMethods(element: PsiElement, project: Project): SuperMethodsData? {
        val pyFunction = findContainingPyFunction(element) ?: return null
        val containingClass = findContainingPyClass(pyFunction) ?: return null

        val file = pyFunction.containingFile?.virtualFile
        val methodData = MethodData(
            name = getName(pyFunction) ?: "unknown",
            signature = buildMethodSignature(pyFunction),
            containingClass = getQualifiedName(containingClass) ?: getName(containingClass) ?: "unknown",
            file = file?.let { getRelativePath(project, it) } ?: "unknown",
            line = getLineNumber(project, pyFunction) ?: 0,
            column = getColumnNumber(project, pyFunction) ?: 0,
            language = "Python",
            pointerTarget = pyFunction
        )

        val hierarchy = buildHierarchy(project, pyFunction)

        return SuperMethodsData(
            method = methodData,
            hierarchy = hierarchy
        )
    }

    private fun buildHierarchy(
        project: Project,
        pyFunction: PsiElement,
        visited: MutableSet<String> = mutableSetOf(),
        depth: Int = 1
    ): List<SuperMethodData> {
        val hierarchy = mutableListOf<SuperMethodData>()

        try {
            // Find super methods by looking at parent classes
            val containingClass = findContainingPyClass(pyFunction) ?: return emptyList()
            val methodName = getName(pyFunction) ?: return emptyList()

            val superClasses = getSuperClasses(containingClass)
            superClasses?.filterIsInstance<PsiElement>()?.forEach { superClass ->
                val superClassName = getQualifiedName(superClass) ?: getName(superClass)
                val key = "$superClassName.$methodName"
                if (key in visited) return@forEach
                visited.add(key)

                // Find method with same name in superclass
                val superMethod = findMethodInClass(superClass, methodName)
                if (superMethod != null) {
                    val file = superMethod.containingFile?.virtualFile

                    hierarchy.add(SuperMethodData(
                        name = methodName,
                        signature = buildMethodSignature(superMethod),
                        containingClass = superClassName ?: "unknown",
                        containingClassKind = "CLASS",
                        file = file?.let { getRelativePath(project, it) },
                        line = getLineNumber(project, superMethod),
                        column = getColumnNumber(project, superMethod),
                        isInterface = false,
                        depth = depth,
                        language = "Python",
                        pointerTarget = superMethod
                    ))

                    hierarchy.addAll(buildHierarchy(project, superMethod, visited, depth + 1))
                }
            }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            // Handle gracefully
        }

        return hierarchy
    }

    private fun buildMethodSignature(pyFunction: PsiElement): String {
        return try {
            val getParameterListMethod = pyFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(pyFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    getNameMethod.invoke(param) as? String
                } catch (e: Exception) {
                    e.rethrowIfControlFlow()
                    null
                }
            }.joinToString(", ")

            val functionName = getName(pyFunction) ?: "unknown"
            "$functionName($params)"
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            getName(pyFunction) ?: "unknown"
        }
    }
}

/**
 * Python implementation of [StructureHandler].
 *
 * Extracts the hierarchical structure of Python source files including
 * classes, functions, and their nesting relationships.
 *
 * Uses reflection to access Python PSI classes to avoid compile-time dependencies.
 */
class PythonStructureHandler : BasePythonHandler<List<StructureNode>>(), StructureHandler {

    companion object {
        private val LOG = logger<PythonStructureHandler>()
    }

    private fun getEndLineNumber(project: Project, element: PsiElement): Int? {
        val file = element.containingFile?.virtualFile ?: return null
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(file) ?: return null
        val endOffset = element.textRange?.endOffset ?: return null
        if (endOffset <= 0 || endOffset > document.textLength) return null
        return document.getLineNumber(endOffset - 1) + 1
    }

    override val languageId = "Python"

    override fun canHandle(element: PsiElement): Boolean {
        return isAvailable() && isPythonLanguage(element)
    }

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyClassClass != null

    override fun getFileStructure(file: PsiFile, project: Project): List<StructureNode> {
        val structure = mutableListOf<StructureNode>()

        try {
            val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
            if (!pyFileClass.isInstance(file)) {
                LOG.debug("File is not a PyFile: ${file.javaClass.name}, language: ${file.language.id}")
                return emptyList()
            }

            // Use PsiTreeUtil to find all top-level classes and functions
            // This is more reliable than calling getClasses()/getFunctions() which may not exist

            @Suppress("UNCHECKED_CAST")
            val classes = PsiTreeUtil.findChildrenOfType(file, pyClassClass as Class<PsiElement>)
            LOG.debug("Found ${classes?.size ?: 0} classes in Python file")

            classes?.forEach { pyClass ->
                // Only include top-level classes (not nested ones initially)
                if (isTopLevel(pyClass, file)) {
                    structure.add(extractClassStructure(pyClass, project))
                }
            }

            @Suppress("UNCHECKED_CAST")
            val functions = PsiTreeUtil.findChildrenOfType(file, pyFunctionClass as Class<PsiElement>)
            LOG.debug("Found ${functions?.size ?: 0} functions in Python file")

            functions?.forEach { pyFunction ->
                // Only include top-level functions (not class methods)
                if (isTopLevel(pyFunction, file)) {
                    structure.add(extractFunctionStructure(pyFunction, project))
                }
            }

        } catch (e: ClassNotFoundException) {
            LOG.warn("Python PSI class not found: ${e.message}")
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            LOG.warn("Failed to extract Python file structure: ${e.message}, ${e.javaClass.simpleName}")
        }

        return structure.sortedBy { it.line }
    }

    /**
     * Check if an element is a top-level element (not nested inside a class).
     */
    private fun isTopLevel(element: PsiElement, file: PsiFile): Boolean {
        // Walk up the tree from element to file, checking if we pass through a PyClass
        var current: PsiElement? = element.parent
        while (current != null && current != file) {
            if (isPyClass(current)) {
                return false // Nested inside a class
            }
            current = current.parent
        }
        return true
    }

    private fun extractClassStructure(pyClass: PsiElement, project: Project): StructureNode {
        val children = mutableListOf<StructureNode>()

        try {
            // Get class methods
            val getMethodsMethod = pyClass.javaClass.getMethod("getMethods")
            val methods = getMethodsMethod.invoke(pyClass) as? Array<*> ?: emptyArray<Any?>()

            for (method in methods) {
                if (method is PsiElement) {
                    children.add(extractFunctionStructure(method, project))
                }
            }

            // Get nested classes
            val getInnerClassesMethod = pyClass.javaClass.getMethod("getInnerClasses")
            val innerClasses = getInnerClassesMethod.invoke(pyClass) as? List<*> ?: emptyList<Any?>()

            for (innerClass in innerClasses) {
                if (innerClass is PsiElement) {
                    children.add(extractClassStructure(innerClass, project))
                }
            }

        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            LOG.warn("Failed to extract Python class structure: ${e.message}")
        }

        val name = getName(pyClass) ?: "unknown"

        return StructureNode(
            name = name,
            kind = StructureKind.CLASS,
            modifiers = getPythonModifiers(pyClass),
            signature = buildClassSignature(pyClass),
            line = getLineNumber(project, pyClass) ?: 0,
            endLine = getEndLineNumber(project, pyClass),
            children = children.sortedBy { it.line },
            pointerTarget = pyClass
        )
    }

    private fun extractFunctionStructure(pyFunction: PsiElement, project: Project): StructureNode {
        val name = getName(pyFunction) ?: "unknown"

        return StructureNode(
            name = name,
            kind = StructureKind.FUNCTION,
            modifiers = getPythonModifiers(pyFunction),
            signature = buildFunctionSignature(pyFunction),
            line = getLineNumber(project, pyFunction) ?: 0,
            endLine = getEndLineNumber(project, pyFunction),
            pointerTarget = pyFunction
        )
    }

    private fun getPythonModifiers(element: PsiElement): List<String> {
        val modifiers = mutableListOf<String>()

        try {
            // Check for decorators using reflection
            val hasDecoratorMethod = element.javaClass.getMethod("hasDecorator", String::class.java)

            if (hasDecoratorMethod.invoke(element, "property") as? Boolean == true) {
                modifiers.add("@property")
            }
            if (hasDecoratorMethod.invoke(element, "staticmethod") as? Boolean == true) {
                modifiers.add("@staticmethod")
            }
            if (hasDecoratorMethod.invoke(element, "classmethod") as? Boolean == true) {
                modifiers.add("@classmethod")
            }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            // Ignore
        }

        return modifiers
    }

    private fun buildClassSignature(pyClass: PsiElement): String {
        return try {
            val superClasses = getSuperClasses(pyClass) ?: emptyArray<Any?>()

            if (superClasses.isNotEmpty()) {
                val names = superClasses.mapNotNull {
                    val element = it as? PsiElement
                    if (element != null) {
                        getQualifiedName(element) ?: getName(element)
                    } else null
                }
                return "(${names.joinToString(", ")})"
            }
            ""
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            ""
        }
    }

    private fun buildFunctionSignature(pyFunction: PsiElement): String {
        return try {
            val getParameterListMethod = pyFunction.javaClass.getMethod("getParameterList")
            val parameterList = getParameterListMethod.invoke(pyFunction)
            val getParametersMethod = parameterList.javaClass.getMethod("getParameters")
            val parameters = getParametersMethod.invoke(parameterList) as? Array<*> ?: emptyArray<Any?>()

            val params = parameters.filterIsInstance<PsiElement>().mapNotNull { param ->
                try {
                    val getNameMethod = param.javaClass.getMethod("getName")
                    getNameMethod.invoke(param) as? String
                } catch (e: Exception) {
                    e.rethrowIfControlFlow()
                    null
                }
            }.joinToString(", ")

            "($params)"
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            "()"
        }
    }
}

/**
 * Python implementation of [SymbolReferenceHandler].
 *
 * Resolves fully-qualified Python symbol references to PSI elements using the
 * Python stub indices. Supported forms:
 * - `pkg.mod.ClassName`              — class (resolved via [PyClassNameIndex.findClass])
 * - `pkg.mod.function_name`          — module-level function (resolved via [PyFunctionNameIndex], filtered by qualified name)
 * - `pkg.mod.ClassName.method_name`  — method (same function index path; a method's qualified name is `pkg.mod.ClassName.method`)
 * - `pkg.mod.ClassName#member_name`  — method or class/instance attribute of the named class
 *
 * Parameter lists are not supported (Python has no overload-by-signature); a
 * symbol containing `(` is rejected. A bare name without a module qualifier is
 * also rejected to avoid bare-name ambiguity — use the position-based path for
 * those, or qualify the symbol.
 *
 * All Python PSI access is reflective to avoid a compile-time dependency on the
 * Python plugin, mirroring the rest of [PythonHandlers].
 */
class PythonSymbolReferenceHandler(
    private val findClassByQName: (String, Project) -> List<PsiNamedElement> = { q, p -> defaultFindClassByQName(q, p) },
    private val findFunctionsByQualifiedName: (String, Project) -> List<PsiNamedElement> = { q, p -> defaultFindFunctionsByQualifiedName(q, p) },
    private val findAttributeInClass: (PsiElement, String) -> PsiNamedElement? = { c, n -> defaultFindAttributeInClass(c, n) }
) : BasePythonHandler<PsiNamedElement>(), SymbolReferenceHandler {

    companion object {
        private val LOG = logger<PythonSymbolReferenceHandler>()

        // Dotted path with at least two segments (module qualifier + name). Allows `_` and digits.
        private const val IDENTIFIER = """[A-Za-z_][A-Za-z0-9_]*"""
        private const val DOTTED_PATH = """$IDENTIFIER(\.$IDENTIFIER)+"""
        internal val PYTHON_SYMBOL_PATTERN = """^$DOTTED_PATH(#$IDENTIFIER)?$""".toRegex()

        private val SYMBOL_EXAMPLES = listOf(
            "'pkg.mod.ClassName'",
            "'pkg.mod.function_name'",
            "'pkg.mod.ClassName.method_name'",
            "'pkg.mod.ClassName#attribute_name'"
        )

        // Reflective default: PyClassNameIndex.findByQualifiedName(qName, project, scope) -> List<PyClass>.
        // The old findClass(qName, project) was removed (PY-63989). Project scope is searched first so a
        // project symbol wins over a dependency/SDK/stub with the same qualified path; allScope is the fallback.
        // Returns ALL matches (project hits first, then allScope-only hits) so the caller can report ambiguity.
        private fun defaultFindClassByQName(qName: String, project: Project): List<PsiNamedElement> {
            val indexClass = pythonClassNameIndexClass ?: return emptyList()
            val projectScope = projectScope(project) ?: return emptyList()
            val allScope = allScope(project) ?: return emptyList()
            val projectHits = findByQualifiedNameReflective(indexClass, qName, project, projectScope)
            if (projectHits.isNotEmpty()) return projectHits
            return findByQualifiedNameReflective(indexClass, qName, project, allScope)
        }

        private fun findByQualifiedNameReflective(
            indexClass: Class<*>, qName: String, project: Project, scope: GlobalSearchScope
        ): List<PsiNamedElement> = try {
            val byQName = indexClass.getMethod(
                "findByQualifiedName", String::class.java, Project::class.java, GlobalSearchScope::class.java
            )
            ((byQName.invoke(null, qName, project, scope) as? Collection<*>)?.filterIsInstance<PsiNamedElement>()
                ?: runFindByShortNameAndFilter(indexClass, qName, project, scope))
        } catch (e: NoSuchMethodException) {
            runFindByShortNameAndFilter(indexClass, qName, project, scope)
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            LOG.warn("PyClassNameIndex lookup failed for '$qName': ${e.message}")
            emptyList()
        }

        private fun runFindByShortNameAndFilter(
            indexClass: Class<*>, qName: String, project: Project, scope: GlobalSearchScope
        ): List<PsiNamedElement> = try {
            val find = indexClass.getMethod("find", String::class.java, Project::class.java, GlobalSearchScope::class.java)
            (find.invoke(null, qName.substringAfterLast('.'), project, scope) as? Collection<*>).orEmpty()
                .filterIsInstance<PsiNamedElement>()
                .filter { getQualifiedNameReflective(it) == qName }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            emptyList()
        }

        // Reflective default: PyFunctionNameIndex.findByQualifiedName(qName, project, scope) -> List<PyFunction> (filters by
        // qualified name inside the index call). Project scope first, allScope fallback, so a project function does not
        // hide a dependency/library function with the same short name but a different qualified path.
        private fun defaultFindFunctionsByQualifiedName(qName: String, project: Project): List<PsiNamedElement> {
            val indexClass = pythonFunctionNameIndexClass ?: return emptyList()
            val projectScope = projectScope(project) ?: return emptyList()
            val projectHits = findFunctionsByQualifiedNameReflective(indexClass, qName, project, projectScope)
            if (projectHits.isNotEmpty()) return projectHits
            val allScope = allScope(project) ?: return emptyList()
            return findFunctionsByQualifiedNameReflective(indexClass, qName, project, allScope)
        }

        private fun findFunctionsByQualifiedNameReflective(
            indexClass: Class<*>, qName: String, project: Project, scope: GlobalSearchScope
        ): List<PsiNamedElement> = try {
            val byQName = indexClass.getMethod(
                "findByQualifiedName", String::class.java, Project::class.java, GlobalSearchScope::class.java
            )
            (byQName.invoke(null, qName, project, scope) as? Collection<*>)?.filterIsInstance<PsiNamedElement>()
                ?: findFunctionsByShortNameAndFilterReflective(indexClass, qName, project, scope)
        } catch (e: NoSuchMethodException) {
            // Old SDK fallback: PyFunctionNameIndex.find(shortName, project, scope) + getQualifiedName filter.
            findFunctionsByShortNameAndFilterReflective(indexClass, qName, project, scope)
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            LOG.warn("PyFunctionNameIndex lookup failed for '$qName': ${e.message}")
            emptyList()
        }

        private fun findFunctionsByShortNameAndFilterReflective(
            indexClass: Class<*>, qName: String, project: Project, scope: GlobalSearchScope
        ): List<PsiNamedElement> = try {
            val find = indexClass.getMethod("find", String::class.java, Project::class.java, GlobalSearchScope::class.java)
            (find.invoke(null, qName.substringAfterLast('.'), project, scope) as? Collection<*>).orEmpty()
                .filterIsInstance<PsiNamedElement>()
                .filter { getQualifiedNameReflective(it) == qName }
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            emptyList()
        }

        // Reflective default: PyClass members reachable as `Class#name`, following inheritance (inherited=true).
        // Order: method (findMethodByName(..., true, context), then multiFindMethodByName(..., true, ...) fallback),
        // class attribute findClassAttribute(String, boolean, TypeEvalContext),
        // instance attribute findInstanceAttribute(String, boolean) [2-arg, no TypeEvalContext],
        // @property findProperty(String, boolean, TypeEvalContext) -> Property.getGetter() -> Maybe<PyCallable>.valueOrNull(),
        // nested class findNestedClass(String, boolean).
        private fun defaultFindAttributeInClass(pyClass: PsiElement, name: String): PsiNamedElement? {
            val context = codeAnalysisContextFallback(pyClass.project)
            findMethodInClassInherited(pyClass, name, context)?.let { return it }
            findClassAttributeReflective(pyClass, name, context)?.let { return it }
            findInstanceAttributeReflective(pyClass, name)?.let { return it }
            findPropertyReflective(pyClass, name, context)?.let { return it }
            findNestedClassReflective(pyClass, name)?.let { return it }
            return null
        }

        // Methods with inheritance. BasePythonHandler.findMethodInClass hardcodes inherited=false, which would miss
        // members declared on a superclass, so this handler resolves methods itself with inherited=true.
        private fun findMethodInClassInherited(pyClass: PsiElement, name: String, context: Any?): PsiNamedElement? {
            val contextClass = pythonTypeEvalContextClass ?: return null
            return try {
                val method = pyClass.javaClass.getMethod("findMethodByName", String::class.java, java.lang.Boolean.TYPE, contextClass)
                method.invoke(pyClass, name, true, context) as? PsiNamedElement
            } catch (e: NoSuchMethodException) {
                multiFindMethodByNameReflective(pyClass, name, context)
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                multiFindMethodByNameReflective(pyClass, name, context)
            }
        }

        private fun multiFindMethodByNameReflective(pyClass: PsiElement, name: String, context: Any?): PsiNamedElement? {
            val contextClass = pythonTypeEvalContextClass ?: return null
            return try {
                val method = pyClass.javaClass.getMethod("multiFindMethodByName", String::class.java, java.lang.Boolean.TYPE, contextClass)
                @Suppress("UNCHECKED_CAST")
                (method.invoke(pyClass, name, true, context) as? List<*>)?.firstOrNull() as? PsiNamedElement
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private fun findClassAttributeReflective(pyClass: PsiElement, name: String, context: Any?): PsiNamedElement? {
            val contextClass = pythonTypeEvalContextClass ?: return null
            return try {
                val method = pyClass.javaClass.getMethod("findClassAttribute", String::class.java, java.lang.Boolean.TYPE, contextClass)
                method.invoke(pyClass, name, true, context) as? PsiNamedElement
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private fun findInstanceAttributeReflective(pyClass: PsiElement, name: String): PsiNamedElement? {
            return try {
                val method = pyClass.javaClass.getMethod("findInstanceAttribute", String::class.java, java.lang.Boolean.TYPE)
                method.invoke(pyClass, name, true) as? PsiNamedElement
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private fun findPropertyReflective(pyClass: PsiElement, name: String, context: Any?): PsiNamedElement? {
            val contextClass = pythonTypeEvalContextClass ?: return null
            return try {
                val method = pyClass.javaClass.getMethod("findProperty", String::class.java, java.lang.Boolean.TYPE, contextClass)
                val property = method.invoke(pyClass, name, true, context) ?: return null
                // Property.getGetter() returns com.jetbrains.python.toolbox.Maybe<PyCallable>; unwrap via valueOrNull().
                val maybe = property.javaClass.getMethod("getGetter").invoke(property) ?: return null
                val getter = maybe.javaClass.getMethod("valueOrNull").invoke(maybe) ?: return null
                getter as? PsiNamedElement
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private fun findNestedClassReflective(pyClass: PsiElement, name: String): PsiNamedElement? {
            return try {
                val method = pyClass.javaClass.getMethod("findNestedClass", String::class.java, java.lang.Boolean.TYPE)
                method.invoke(pyClass, name, true) as? PsiNamedElement
            } catch (e: NoSuchMethodException) {
                null
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private fun projectScope(project: Project): GlobalSearchScope? = try {
            GlobalSearchScope.projectScope(project)
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }

        private fun allScope(project: Project): GlobalSearchScope? = try {
            GlobalSearchScope.allScope(project)
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }

        private fun getQualifiedNameReflective(element: PsiElement): String? = try {
            val m = element.javaClass.getMethod("getQualifiedName")
            m.invoke(element) as? String
        } catch (e: Exception) {
            e.rethrowIfControlFlow()
            null
        }

        private fun codeAnalysisContextFallback(project: Project): Any? {
            val typeEvalContextClass = pythonTypeEvalContextClass ?: return null
            return try {
                val method = typeEvalContextClass.getMethod("codeInsightFallback", Project::class.java)
                method.invoke(null, project)
            } catch (e: Exception) {
                e.rethrowIfControlFlow()
                null
            }
        }

        private val pythonClassNameIndexClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.stubs.PyClassNameIndex") } catch (_: ClassNotFoundException) { null }
        }
        private val pythonFunctionNameIndexClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.stubs.PyFunctionNameIndex") } catch (_: ClassNotFoundException) { null }
        }
        private val pythonTypeEvalContextClass: Class<*>? by lazy {
            try { Class.forName("com.jetbrains.python.psi.types.TypeEvalContext") } catch (_: ClassNotFoundException) { null }
        }
    }

    override val languageId = "Python"
    override val languageName = "Python"

    override fun canHandle(element: PsiElement): Boolean = isAvailable() && isPythonLanguage(element)

    override fun isAvailable(): Boolean = PluginDetectors.python.isAvailable && pyClassClass != null

    override fun resolveSymbol(project: Project, symbol: String): Result<PsiNamedElement> {
        val s = symbol.trim()
        if (s.isEmpty() || '(' in s || ')' in s || !PYTHON_SYMBOL_PATTERN.matches(s)) {
            return ErrorMessages.invalidSymbolFormat(s, SYMBOL_EXAMPLES).toArgumentFailure()
        }

        val hashIndex = s.indexOf('#')
        return if (hashIndex >= 0) {
            resolveMember(project, s, hashIndex)
        } else {
            resolveByQualifiedName(project, s)
        }
    }

    private fun resolveByQualifiedName(project: Project, qName: String): Result<PsiNamedElement> {
        val candidates = mutableListOf<PsiNamedElement>()

        // Class and function hits come back project-scope-first, filtered by qualified name inside the index call,
        // so a project symbol does not hide a dependency/library symbol with the same short name but a different qName.
        candidates += findClassByQName(qName, project)
        candidates += findFunctionsByQualifiedName(qName, project)

        val distinct = candidates.distinctBy { elementKey(it) }
        return when {
            distinct.isEmpty() -> ErrorMessages.typeNotFound(qName, project.name).toArgumentFailure()
            distinct.size == 1 -> Result.success(distinct.single())
            else -> ErrorMessages.multipleMethodsMatch(
                qName, qName,
                distinct.map { describe(it) }
            ).toArgumentFailure()
        }
    }

    private fun resolveMember(project: Project, symbol: String, hashIndex: Int): Result<PsiNamedElement> {
        val containerQName = symbol.substring(0, hashIndex)
        val memberName = symbol.substring(hashIndex + 1)

        val classes = findClassByQName(containerQName, project)
        val pyClass = classes.firstOrNull()
            ?: return ErrorMessages.typeNotFound(containerQName, project.name).toArgumentFailure()
        if (classes.size > 1) {
            return ErrorMessages.multipleMethodsMatch(
                containerQName, containerQName, classes.map { describe(it) }
            ).toArgumentFailure()
        }

        // defaultFindAttributeInClass resolves method (inherited=true), class/instance attribute, @property, nested class.
        findAttributeInClass(pyClass, memberName)?.let { return Result.success(it) }

        return ErrorMessages.memberNotFoundInType(memberName, containerQName).toArgumentFailure()
    }

    private fun describe(element: PsiNamedElement): String {
        val qn = getQualifiedName(element) ?: getName(element) ?: "unknown"
        val file = element.containingFile?.virtualFile?.path ?: "?"
        return "$qn @ $file"
    }

    private fun elementKey(element: PsiElement): String =
        "${element.containingFile?.virtualFile?.path ?: "?"}:${element.textOffset}"
}
