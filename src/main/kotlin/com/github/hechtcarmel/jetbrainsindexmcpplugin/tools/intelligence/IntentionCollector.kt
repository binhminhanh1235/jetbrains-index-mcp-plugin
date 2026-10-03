package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * Shared helper for collecting available intentions and quick fixes at a file position.
 * Used by [ApplyQuickFixTool] and [GetDiagnosticsTool].
 */
object IntentionCollector {

    const val DEFAULT_MAX_INTENTIONS = 50

    /**
     * Collects all available actions (quick fixes from highlights + general intentions) at the given offset.
     */
    fun collectAvailableActions(
        project: Project,
        editor: Editor,
        psiFile: PsiFile,
        offset: Int,
        highlights: List<HighlightInfo>,
        maxGeneralIntentions: Int = DEFAULT_MAX_INTENTIONS
    ): List<IntentionAction> {
        val actions = mutableListOf<IntentionAction>()

        // Quick fixes from highlight descriptors at the position
        highlights
            .asSequence()
            .filter { it.startOffset <= offset && it.endOffset >= offset }
            .forEach { highlight ->
                highlight.findRegisteredQuickFix<Any> { descriptor, _ ->
                    val action = descriptor.action
                    try {
                        if (action.isAvailable(project, editor, psiFile)) {
                            actions.add(action)
                        }
                    } catch (_: Exception) { }
                    null
                }
            }

        // General intention actions at position
        if (psiFile.findElementAt(offset) != null) {
            IntentionManager.getInstance()
                .getAvailableIntentions()
                .take(maxGeneralIntentions)
                .forEach { action ->
                    try {
                        if (action.isAvailable(project, editor, psiFile)) {
                            actions.add(action)
                        }
                    } catch (_: Exception) { }
                }
        }

        return actions.distinctBy { it.text }
    }
}
