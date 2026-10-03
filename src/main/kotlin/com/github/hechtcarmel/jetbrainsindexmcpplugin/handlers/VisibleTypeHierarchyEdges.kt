package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PsiUtils
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.util.ArrayDeque

/**
 * Whether [expectedParent] is a nearest visible ancestor of [candidate] in [searchScope].
 * Native deep searches can return a visible descendant whose intermediate parents are excluded
 * from the response. Collapse only those excluded parents; an included parent remains a separate
 * edge for the hierarchy tool to expand. The requested root may itself be outside the scope.
 *
 * [directParents] supplies native superclass/interface relationships. The shared traversal neither
 * materializes descendants nor imposes a silent depth cap, and remains cancellable through cycles
 * and long excluded chains. Callers still bound their native query with a stopping Processor.
 */
@RequiresReadLock
internal fun isVisibleSubtypeOf(
    candidate: PsiElement,
    expectedParent: PsiElement,
    searchScope: GlobalSearchScope,
    directParents: (PsiElement) -> List<PsiElement>
): Boolean {
    ProgressManager.checkCanceled()
    val expectedTarget = PsiUtils.resolveNavigationTarget(expectedParent)
    val pending = ArrayDeque(directParents(candidate))
    val visited = mutableSetOf<PsiElement>()
    while (pending.isNotEmpty()) {
        ProgressManager.checkCanceled()
        val parentTarget = PsiUtils.resolveNavigationTarget(pending.removeFirst())
        if (!visited.add(parentTarget)) continue
        if (parentTarget === expectedTarget || (
            parentTarget.containingFile?.virtualFile != null &&
                parentTarget.containingFile?.virtualFile == expectedTarget.containingFile?.virtualFile &&
                parentTarget.textOffset == expectedTarget.textOffset
            )) return true
        if (!shouldIncludeNavigationElement(searchScope, parentTarget)) {
            pending.addAll(directParents(parentTarget))
        }
    }
    return false
}
