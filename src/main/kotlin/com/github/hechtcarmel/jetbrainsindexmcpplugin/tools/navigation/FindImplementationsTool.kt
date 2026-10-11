package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScopeResolver
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.PaginationService
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CompactImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationLocation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.ImplementationResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tool for finding implementations of interfaces, abstract classes, or methods across multiple languages.
 *
 * Supports: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala
 *
 * Delegates to language-specific handlers via [LanguageHandlerRegistry].
 */
class FindImplementationsTool : AbstractMcpTool() {

    companion object {
        private const val DEFAULT_PAGE_SIZE = 25
        private const val MAX_PAGE_SIZE = PaginationService.MAX_PAGE_SIZE
    }

    override val name = "ide_find_implementations"

    override val description = """
        Find all implementations of an interface, abstract class, or abstract method. Use to discover concrete implementations when working with abstractions.

        Languages: Java, Kotlin, Python, JavaScript, TypeScript, PHP, Rust, Scala.

        Returns: list of implementing classes/methods with file paths, line/column numbers, and kind (class/method).

        Supports pagination: first call returns results + nextCursor. Pass cursor to get the next page.

        Target (mutually exclusive):
        - target: nested selector containing exactly one of symbolId, position {file, line, column}, or qualifiedName + language (necessary for fresh search, ignored when cursor is provided)
        - top-level symbolId: opaque handle returned by a previous semantic call (necessary for fresh search, ignored when cursor is provided)
        - top-level file + line + column: position-based lookup (necessary for fresh search, ignored when cursor is provided)
        - top-level language + symbol: fully qualified symbol reference (supported languages: ${supportedSymbolReferenceLanguagesDescription()}; necessary for fresh search, ignored when cursor is provided)
        - cursor: pagination cursor from a previous response

        Parameters: scope (optional, default: "project_files"; supported: project_files, project_and_libraries, project_production_files, project_test_files), compact (optional, default: true), pageSize (optional, default: 25, max: 500).

        Example: {"file": "src/Repository.java", "line": 8, "column": 18}
        Example: {"target": {"qualifiedName": "com.example.Repository", "language": "Java"}}
        Example: {"language": "Java", "symbol": "com.example.Repository", "scope": "project_and_libraries"}
        Example: {"language": "JavaScript", "symbol": "src/interfaces#IRepository"}
        Example: {"language": "PHP", "symbol": "\\App\\Contracts\\Repository"}
        """.trimIndent()

    override val inputSchema: ToolSchema = SchemaBuilder.tool()
        .projectPath()
        .target()
        .symbolId()
        .file(required = false, description = "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin. Required for position-based lookup.")
        .lineAndColumn(required = false)
        .languageAndSymbol(required = false)
        .scopeProperty("Search scope. Default: project_files.")
        .booleanProperty(ParamNames.INCLUDE_GENERATED, "Include implementations in generated sources (KSP/Dagger/annotation-processor output). Default: false.")
        .booleanProperty("compact", "Compact mode: returns lightweight formatted strings (file:line:col [kind] symbol) instead of full structured JSON objects, reducing output tokens by 50-70%. Default: true.", required = false)
        .stringProperty("cursor", "Pagination cursor from a previous response. When provided, returns the next page of results. Search parameters are ignored; project_path and pageSize may still be provided.")
        .intProperty("pageSize", "Results per page. Default: $DEFAULT_PAGE_SIZE, max: $MAX_PAGE_SIZE.")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        val compact = arguments["compact"]?.jsonPrimitive?.booleanOrNull ?: true
        val cursor = optionalStringArg(arguments, ParamNames.CURSOR)
        if (cursor != null) {
            val pageSize = resolveExplicitPageSize(arguments)
            val pageResult = getPageFromCache(cursor, pageSize, project)
            return if (compact) {
                buildPaginatedResult<ImplementationLocation, CompactImplementationResult>(pageResult) { items, page ->
                    CompactImplementationResult(
                        implementations = items.map { "${it.file}:${it.line}:${it.column} [${it.kind}] ${it.qualifiedName ?: it.name}" },
                        totalCount = page.totalCollected,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore
                    )
                }
            } else {
                buildPaginatedResult<ImplementationLocation, ImplementationResult>(pageResult) { items, page ->
                    ImplementationResult(
                        implementations = items,
                        totalCount = page.totalCollected,
                        nextCursor = page.nextCursor,
                        hasMore = page.hasMore,
                        totalCollected = page.totalCollected,
                        offset = page.offset,
                        pageSize = page.pageSize,
                        stale = page.stale
                    )
                }
            }
        }

        val pageSize = resolvePageSize(arguments, DEFAULT_PAGE_SIZE)
        val rawScope = rawScopeValue(arguments[ParamNames.SCOPE])
        val scope = try {
            BuiltInSearchScopeResolver.parse(arguments, BuiltInSearchScope.PROJECT_FILES)
        } catch (_: IllegalArgumentException) {
            return createInvalidScopeError(rawScope)
        } catch (_: IllegalStateException) {
            return createInvalidScopeError(rawScope)
        }
        val excludeGenerated = resolveExcludeGenerated(arguments, default = false)
        requireSmartMode(project)

        val cursorToken = suspendingReadAction {
            val element = resolveElementFromArguments(
                project,
                arguments,
                allowLibraryFilesForPosition = true,
                allowSymbolId = true
            ).getOrElse {
                return@suspendingReadAction null to createErrorResult(it.message ?: ErrorMessages.COULD_NOT_RESOLVE_SYMBOL)
            }

            val handler = LanguageHandlerRegistry.getImplementationsHandler(element)
            if (handler == null) {
                return@suspendingReadAction null to createErrorResult(
                    "No implementations handler available for language: ${element.language.id}. " +
                    "Supported languages: ${LanguageHandlerRegistry.getSupportedLanguagesForImplementations()}"
                )
            }

            val implementations = handler.findImplementations(element, project, scope, excludeGenerated)
            if (implementations == null) {
                val isSymbolMode = optionalStringArg(arguments, ParamNames.LANGUAGE) != null
                return@suspendingReadAction null to createErrorResult(
                    if (isSymbolMode) "No method or class found for the specified symbol"
                    else "No method or class found at position"
                )
            }

            val implementationLocations = implementations.map { impl ->
                ImplementationLocation(
                    name = impl.name,
                    file = impl.file,
                    line = impl.line,
                    column = impl.column,
                    kind = impl.kind,
                    language = impl.language,
                    qualifiedName = impl.qualifiedName,
                    symbolId = null,
                    pointerTarget = impl.pointerTarget
                )
            }

            val serializedResults = implementationLocations.map { impl ->
                PaginationService.SerializedResult(
                    key = "${impl.file}:${impl.line}:${impl.column}:${impl.name}",
                    data = json.encodeToJsonElement(impl),
                    symbolPointer = impl.pointerTarget?.let {
                        SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it)
                    }
                )
            }

            val paginationService = ApplicationManager.getApplication().getService(PaginationService::class.java)
            val token = paginationService.createCursor(
                toolName = name,
                results = serializedResults,
                seenKeys = serializedResults.map { it.key }.toSet(),
                searchExtender = null,
                psiModCount = PsiModificationTracker.getInstance(project).modificationCount,
                project = project
            )

            token to null
        }

        val (token, errorResult) = cursorToken
        if (errorResult != null) return errorResult

        val pageResult = getPageFromCache(token!!, pageSize, project)
        return if (compact) {
            buildPaginatedResult<ImplementationLocation, CompactImplementationResult>(pageResult) { items, page ->
                CompactImplementationResult(
                    implementations = items.map { "${it.file}:${it.line}:${it.column} [${it.kind}] ${it.qualifiedName ?: it.name}" },
                    totalCount = page.totalCollected,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore
                )
            }
        } else {
            buildPaginatedResult<ImplementationLocation, ImplementationResult>(pageResult) { items, page ->
                ImplementationResult(
                    implementations = items,
                    totalCount = page.totalCollected,
                    nextCursor = page.nextCursor,
                    hasMore = page.hasMore,
                    totalCollected = page.totalCollected,
                    offset = page.offset,
                    pageSize = page.pageSize,
                    stale = page.stale
                )
            }
        }
    }


}
