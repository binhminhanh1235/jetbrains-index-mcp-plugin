package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Runs a write-safe EDT action without requiring the EDT to process cancellation first. */
internal suspend fun <T> cancellableEdtAction(action: () -> T): T {
    if (ApplicationManager.getApplication().isDispatchThread) return action()
    return suspendCancellableCoroutine { continuation ->
        // A queued child of the request would keep its cancelled parent waiting until EDT
        // dispatches it. Keep the request context (including its server epoch), but link
        // cancellation explicitly so the waiting caller can finish before EDT is available.
        val queuedAction = CoroutineScope(continuation.context.minusKey(Job)).launch(
            Dispatchers.EDT + ModalityState.nonModal().asContextElement()
        ) {
            continuation.resumeWith(runCatching(action))
        }
        continuation.invokeOnCancellation { queuedAction.cancel() }
    }
}
