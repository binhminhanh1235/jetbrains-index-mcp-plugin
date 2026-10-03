package com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.java

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod

/** Loaded only after the universal hierarchy helper has verified that Java PSI is available. */
internal fun haveSameHierarchyMethodSignature(first: PsiElement, second: PsiElement): Boolean {
    val left = first as PsiMethod
    val right = second as PsiMethod
    return left.name == right.name &&
        left.parameterList.parameters.map { it.type.canonicalText } ==
        right.parameterList.parameters.map { it.type.canonicalText }
}
