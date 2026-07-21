package com.jetbrains.python.psi

import com.intellij.psi.PsiPolyVariantReference

interface PyReferenceExpression : PyExpression {
    fun getQualifier(): PyExpression?
    override fun getReference(): PsiPolyVariantReference
}
