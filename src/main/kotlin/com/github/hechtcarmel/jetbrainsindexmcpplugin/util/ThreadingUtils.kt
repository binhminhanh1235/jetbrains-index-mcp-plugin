package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ThreadingUtils {

    suspend fun <T> readActionSuspend(action: () -> T): T {
        return com.intellij.openapi.application.readAction { action() }
    }

    suspend fun writeActionSuspend(
        project: Project,
        commandName: String,
        action: () -> Unit
    ) {
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(
                project,
                commandName,
                null,
                { action() }
            )
        }
    }

    suspend fun <T> runOnEdt(action: () -> T): T =
        withContext(Dispatchers.EDT) { action() }

    fun runOnEdtAsync(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(action, ModalityState.any())
    }

    fun isDumbMode(project: Project): Boolean {
        return DumbService.isDumb(project)
    }

    fun <T> computeInReadAction(action: () -> T): T {
        return ReadAction.compute<T, Throwable>(action)
    }

    fun runInWriteAction(project: Project, commandName: String, action: () -> Unit) {
        WriteCommandAction.runWriteCommandAction(project, commandName, null, { action() })
    }
}
