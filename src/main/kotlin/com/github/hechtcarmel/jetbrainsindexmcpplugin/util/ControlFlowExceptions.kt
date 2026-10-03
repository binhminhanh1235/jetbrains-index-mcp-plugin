package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

/** Optional-plugin reflection must not turn a cancelled/index-unavailable search into no results. */
internal fun Throwable.rethrowIfControlFlow() {
    val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        when (current) {
            is ProcessCanceledException -> throw current
            is CancellationException -> throw current
            is IndexNotReadyException -> throw current
        }
        current = current.cause
    }
}
