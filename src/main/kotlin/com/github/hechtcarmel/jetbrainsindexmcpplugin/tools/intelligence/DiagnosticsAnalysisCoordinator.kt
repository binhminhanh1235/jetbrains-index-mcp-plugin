package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Service(Service.Level.PROJECT)
class DiagnosticsAnalysisCoordinator {

    companion object {
        fun getInstance(project: Project): DiagnosticsAnalysisCoordinator =
            project.getService(DiagnosticsAnalysisCoordinator::class.java) ?: DiagnosticsAnalysisCoordinator()
    }

    private val mainPassesMutex = Mutex()

    suspend fun <T> withMainPassLock(action: suspend () -> T): T {
        return mainPassesMutex.withLock {
            action()
        }
    }
}
