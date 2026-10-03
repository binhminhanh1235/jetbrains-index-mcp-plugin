package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport

import junit.framework.TestCase

class LocalOriginGuardUnitTest : TestCase() {
    fun testLoopbackAliasesEnableHostGuard() {
        assertTrue(isLoopbackBindHost("localhost."))
        assertTrue(isLoopbackBindHost("::ffff:127.0.0.1"))
    }

    fun testHostnameNormalizesTrailingDot() {
        assertEquals("localhost", hostnameOf("LOCALHOST."))
        assertEquals("localhost", hostnameOf("localhost.:29170"))
    }
}
