package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.system.measureTimeMillis

class CancellableBlockingActionTest : McpPlatformTestCase() {

    fun testReturnsBlockingResultOffEdt() = runBlocking {
        assertEquals("result", cancellableBlockingAction {
            assertFalse(ApplicationManager.getApplication().isDispatchThread)
            assertNotNull(ProgressManager.getInstance().progressIndicator)
            "result"
        })
    }

    fun testTimeoutInterruptsBlockingWaitAndWaitsForWorkerExit() = runBlocking {
        val entered = AtomicBoolean()
        val exited = AtomicBoolean()
        val elapsed = measureTimeMillis {
            val result = withTimeoutOrNull(300L) {
                cancellableBlockingAction {
                    entered.set(true)
                    try {
                        CountDownLatch(1).await(5, TimeUnit.SECONDS)
                    } finally {
                        exited.set(true)
                    }
                }
            }
            assertNull("The blocking wait must time out", result)
        }
        assertTrue("The blocking operation must actually run", entered.get())
        assertTrue("Cancellation must join the worker before returning", exited.get())
        assertTrue("Cancellation took ${elapsed}ms", elapsed < 3_000L)
    }

    fun testTimeoutCancelsPlatformIndicator() = runBlocking {
        val observedCancellation = AtomicBoolean()
        val elapsed = measureTimeMillis {
            assertNull(withTimeoutOrNull(300L) {
                cancellableBlockingAction {
                    val indicator = requireNotNull(ProgressManager.getInstance().progressIndicator)
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    // Deliberately use the indicator, not a suspending coroutine primitive.
                    // An interrupt alone cannot stop this platform-style analysis loop.
                    while (!indicator.isCanceled && System.nanoTime() < deadline) {
                        Thread.interrupted()
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                    }
                    observedCancellation.set(indicator.isCanceled)
                    indicator.checkCanceled()
                }
            })
        }
        assertTrue("Coroutine cancellation must reach the platform indicator", observedCancellation.get())
        assertTrue("Cancellation took ${elapsed}ms", elapsed < 3_000L)
    }

    fun testIndependentPlatformCancellationIsPreserved() = runBlocking {
        val cancellation = ProcessCanceledException()
        try {
            cancellableBlockingAction<Unit> { throw cancellation }
            fail("Expected platform cancellation")
        } catch (actual: ProcessCanceledException) {
            assertSame(cancellation, actual)
        }
    }
}
