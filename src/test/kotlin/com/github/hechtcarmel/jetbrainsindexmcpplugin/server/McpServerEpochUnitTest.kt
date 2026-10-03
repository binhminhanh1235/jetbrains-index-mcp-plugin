package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import junit.framework.TestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class McpServerEpochUnitTest : TestCase() {

    fun testCapturedRequestEpochSurvivesThreadHopsAndDoesNotJoinResetSession() = runBlocking {
        val serverEpoch = McpServerEpoch()
        val captured = serverEpoch.capture()

        withContext(Dispatchers.Default + serverEpoch.requestContext(captured)) {
            serverEpoch.advanceAndReset { }

            assertEquals(captured, serverEpoch.expectedForCurrentRequest())
            assertFalse(serverEpoch.isCurrent(captured))
        }
    }
}
