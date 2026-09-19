package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema.SchemaBuilder
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * MCP tool that applies an IntelliJ quick fix / intention action at a given position.
 *
 * The tool leverages the same intention-collection logic as [GetDiagnosticsTool] and
 * applies the selected action via [com.intellij.codeInsight.intention.IntentionAction.invoke].
 *
 * ## Constraints
 * - The target file must be **open in the editor** — IntelliJ's quick-fix infrastructure
 *   requires a live [Editor] instance. If the file is not open, the tool returns an error.
 * - Fixing happens on the EDT inside a write-command action, so it is undo-able.
 *
 * ## Selection strategy
 * Caller may select a fix by:
 * 1. `fixIndex` (0-based, applied in the order diagnostics reports them)
 * 2. `fixName` (case-insensitive substring match against the intention's `text`)
 *
 * If neither is provided, the first available fix is applied.
 */
class ApplyQuickFixTool : AbstractMcpTool() {

    override val name = ToolNames.APPLY_QUICK_FIX

    override val description = """
        Apply an available quick fix or intention action at a specific position in a file.
        The file must be open in the editor. Use ide_diagnostics first to discover available
        intentions/quick fixes at a position, then call this tool to apply one.
        
        Selection: provide fixIndex (0-based index from diagnostics intentions list) OR
        fixName (substring of the fix text). Omit both to apply the first available fix.
        
        Returns: applied fix name, success status, and a hint to call ide_verify_change after.
    """.trimIndent()

    override val inputSchema = SchemaBuilder.tool()
        .projectPath()
        .file(required = true, description = "Project-relative path to the file")
        .intProperty("line", "1-based line number for the quick fix position")
        .intProperty("column", "1-based column number (default: 1)")
        .intProperty("fixIndex", "0-based index of the fix to apply (from ide_diagnostics intentions list). Default: 0")
        .stringProperty("fixName", "Case-insensitive substring of the fix name to apply. Takes precedence over fixIndex.", required = false)
        .booleanProperty("preview", "If true, return available fixes without applying. Default: false")
        .build()

    override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
        requireSmartMode(project)

        val filePath = arguments["file"]?.jsonPrimitive?.content
            ?: return createErrorResult("Missing required parameter: file")
        val line = arguments["line"]?.jsonPrimitive?.intOrNull ?: 1
        val column = arguments["column"]?.jsonPrimitive?.intOrNull ?: 1
        val fixIndex = arguments["fixIndex"]?.jsonPrimitive?.intOrNull ?: 0
        val fixName = arguments["fixName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val preview = arguments["preview"]?.jsonPrimitive?.booleanOrNull ?: false

        // 1. Resolve file
        val virtualFile = resolveFile(project, filePath)
            ?: return createErrorResult("File not found: $filePath")

        // 2. Ensure file is open — quick-fix invocation requires a live editor
        val fileEditorManager = FileEditorManager.getInstance(project)
        val editor: Editor? = fileEditorManager.getEditors(virtualFile)
            .filterIsInstance<TextEditor>()
            .firstOrNull()
            ?.editor

        if (editor == null) {
            return createErrorResult(
                "File '$filePath' is not open in the editor. " +
                "Open the file first (ide_open_file), or use ide_diagnostics to read errors and fix them manually."
            )
        }

        // 3. Sync VFS to pick up any external changes
        edtAction {
            VfsUtil.markDirtyAndRefresh(false, false, false, virtualFile)
        }

        // 4. Collect highlights + intentions (reusing DiagnosticsAnalysisService)
        val analysisResult = DiagnosticsAnalysisService.getInstance(project).analyzeFile(
            virtualFile = virtualFile,
            filePath = filePath,
            severity = "all",
            startLine = null,
            endLine = null,
            maxProblems = 500
        )

        // 5. Collect actionable intentions at position
        val availableActions = suspendingReadAction {
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile)
                ?: return@suspendingReadAction emptyList<AvailableAction>()
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                ?: return@suspendingReadAction emptyList<AvailableAction>()

            collectActionsAtPosition(
                project = project,
                psiFile = psiFile,
                editor = editor,
                line = line,
                column = column,
                highlights = analysisResult.highlights
            )
        }

        if (availableActions.isEmpty()) {
            return createErrorResult(
                "No quick fixes available at $filePath:$line:$column. " +
                "Ensure the file is open and there is a diagnostic or intention at this position."
            )
        }

        // 6. Preview mode — just list without applying
        if (preview) {
            return createJsonResult(ApplyQuickFixResult(
                success = false,
                preview = true,
                appliedFix = null,
                availableFixes = availableActions.map { it.text },
                filePath = filePath,
                message = "Preview mode — ${availableActions.size} fix(es) available. Call again with preview=false to apply."
            ))
        }

        // 7. Select fix
        val selectedAction = if (fixName != null) {
            availableActions.firstOrNull { it.text.contains(fixName, ignoreCase = true) }
                ?: return createErrorResult(
                    "No fix matching '$fixName' found at $filePath:$line:$column. " +
                    "Available: ${availableActions.joinToString(", ") { "\"${it.text}\"" }}"
                )
        } else {
            availableActions.getOrNull(fixIndex)
                ?: return createErrorResult(
                    "Fix index $fixIndex out of range — ${availableActions.size} fix(es) available: " +
                    availableActions.mapIndexed { i, a -> "[$i] ${a.text}" }.joinToString(", ")
                )
        }

        // 8. Apply fix on EDT inside write-command action
        var applyError: String? = null
        suspendingWriteAction(project, "Apply quick fix: ${selectedAction.text}") {
            try {
                selectedAction.action.invoke(project, editor, selectedAction.psiFile)
            } catch (e: Exception) {
                applyError = "Failed to apply fix: ${e.message}"
            }
        }

        if (applyError != null) {
            return createErrorResult(applyError!!)
        }

        return createJsonResult(ApplyQuickFixResult(
            success = true,
            preview = false,
            appliedFix = selectedAction.text,
            availableFixes = availableActions.map { it.text },
            filePath = filePath,
            message = "Fix applied. Call ide_verify_change(\"$filePath\") to confirm zero errors."
        ))
    }

    private data class AvailableAction(
        val text: String,
        val action: com.intellij.codeInsight.intention.IntentionAction,
        val psiFile: PsiFile
    )

    private fun collectActionsAtPosition(
        project: Project,
        psiFile: PsiFile,
        editor: Editor,
        line: Int,
        column: Int,
        highlights: List<HighlightInfo>
    ): List<AvailableAction> {
        val actions = mutableListOf<AvailableAction>()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: return emptyList()
        val offset = getOffset(document, line, column) ?: return emptyList()

        // Quick fixes from highlight descriptors at the position
        highlights
            .asSequence()
            .filter { it.startOffset <= offset && it.endOffset >= offset }
            .forEach { highlight ->
                highlight.findRegisteredQuickFix<Any> { descriptor, _ ->
                    val action = descriptor.action
                    try {
                        if (action.isAvailable(project, editor, psiFile)) {
                            actions.add(AvailableAction(action.text, action, psiFile))
                        }
                    } catch (_: Exception) { }
                    null
                }
            }

        // General intention actions at position
        if (psiFile.findElementAt(offset) != null) {
            com.intellij.codeInsight.intention.IntentionManager.getInstance()
                .getAvailableIntentions()
                .take(50)
                .forEach { action ->
                    try {
                        if (action.isAvailable(project, editor, psiFile)) {
                            actions.add(AvailableAction(action.text, action, psiFile))
                        }
                    } catch (_: Exception) { }
                }
        }

        return actions.distinctBy { it.text }
    }
}

@Serializable
data class ApplyQuickFixResult(
    val success: Boolean,
    val preview: Boolean,
    val appliedFix: String?,
    val availableFixes: List<String>,
    val filePath: String,
    val message: String
)
