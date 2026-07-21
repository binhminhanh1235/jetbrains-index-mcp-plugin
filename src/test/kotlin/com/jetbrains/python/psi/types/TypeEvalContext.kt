package com.jetbrains.python.psi.types

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

open class TypeEvalContext {
    companion object {
        @JvmStatic
        fun codeAnalysis(project: Project, origin: PsiFile?): TypeEvalContext = TypeEvalContext()

        @JvmStatic
        fun userInitiated(project: Project, origin: PsiFile?): TypeEvalContext = TypeEvalContext()

        @JvmStatic
        fun codeInsightFallback(project: Project?): TypeEvalContext = TypeEvalContext()
    }
}
