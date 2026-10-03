package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.asContextElement

/**
 * One lifecycle epoch shared by every server-side handle registry.
 *
 * A dispatcher captures the epoch once and installs it in the request coroutine. Registry
 * mutations are checked under the same monitor as [advanceAndReset], so an old request cannot
 * insert state after a restart boundary. Capturing a new request is also blocked until every
 * registry supplied to [advanceAndReset] has been cleared.
 */
class McpServerEpoch {

    private val monitor = Any()
    private var epoch: Long = 0L
    private val requestEpoch = ThreadLocal<Long?>()

    internal fun capture(): Long = synchronized(monitor) { epoch }

    internal fun requestContext(capturedEpoch: Long = capture()): ThreadContextElement<Long?> =
        requestEpoch.asContextElement(capturedEpoch)

    internal fun expectedForCurrentRequest(): Long = requestEpoch.get() ?: capture()

    internal fun isCurrent(expectedEpoch: Long): Boolean = synchronized(monitor) {
        epoch == expectedEpoch
    }

    /** Runs [action] only while [expectedEpoch] is still the active server epoch. */
    internal fun <T> ifCurrent(
        expectedEpoch: Long,
        stale: () -> T,
        action: () -> T
    ): T = synchronized(monitor) {
        if (epoch == expectedEpoch) action() else stale()
    }

    /**
     * Advances the lifecycle boundary and clears all epoch-owned state as one atomic transition.
     * No dispatcher can capture the new epoch until [reset] has completed.
     */
    internal fun advanceAndReset(reset: () -> Unit): Long = synchronized(monitor) {
        epoch++
        reset()
        epoch
    }

    internal companion object {
        val shared = McpServerEpoch()
    }
}
