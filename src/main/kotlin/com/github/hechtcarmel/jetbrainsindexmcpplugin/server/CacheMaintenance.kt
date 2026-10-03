package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit

/** Releases idle/closed-project payloads even when nobody calls the owning cache again. */
internal class CacheMaintenance(
    owner: Disposable,
    sweep: (() -> Unit)?,
    removeProject: (Project) -> Unit
) : Disposable {
    private val connection = ApplicationManager.getApplication()?.messageBus?.connect(owner)?.also { connection ->
        connection.subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
            override fun projectClosed(project: Project) = removeProject(project)
        })
    }
    private val future = if (connection == null) null else sweep?.let { sweepAction ->
        AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { sweepAction() },
            PaginationService.SWEEP_INTERVAL_MINUTES,
            PaginationService.SWEEP_INTERVAL_MINUTES,
            TimeUnit.MINUTES
        )
    }

    override fun dispose() {
        future?.cancel(false)
        connection?.disconnect()
    }
}

internal enum class CacheEvictionReason {
    TTL, LRU, PROJECT_CLOSED, SESSION_RESET, INVALIDATED
}

/** Internal diagnostics only: a snapshot does not touch LRU order or refresh TTL. */
internal data class CacheStats(
    val entries: Int,
    val hits: Long,
    val misses: Long,
    val insertions: Long,
    val evictions: Map<CacheEvictionReason, Long>,
    val storedPointers: Long = 0,
    val storedResults: Long = 0,
    val maintenanceScans: Long = 0
)

/** Accessed only under the owning registry's monitor. */
internal class CacheCounters {
    var hits = 0L
    var misses = 0L
    var insertions = 0L
    var maintenanceScans = 0L
    private val evictions = mutableMapOf<CacheEvictionReason, Long>()

    fun removed(reason: CacheEvictionReason, count: Int = 1) {
        if (count > 0) evictions[reason] = (evictions[reason] ?: 0L) + count
    }

    fun snapshot(
        entries: Int,
        pointers: Long = 0,
        results: Long = 0
    ) = CacheStats(
        entries, hits, misses, insertions, evictions.toMap(), pointers, results, maintenanceScans
    )
}
