package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import junit.framework.TestCase
import java.net.InetAddress
import java.net.UnknownHostException

class BindHostValidationUnitTest : TestCase() {
    fun testAcceptsIpv6LiteralsIncludingIpv4MappedAddresses() {
        for (host in listOf("::1", "[::1]", "2001:db8::1", "::ffff:127.0.0.1")) {
            assertTrue("Valid IP literal: $host", McpSettingsConfigurable.isValidHost(host))
        }
    }

    fun testRejectsMalformedIpv6() {
        for (host in listOf(":::1", "2001:db8::xyz", "[::1", "::ffff:999.0.0.1")) {
            assertFalse("Malformed IP literal: $host", McpSettingsConfigurable.isValidHost(host))
        }
    }

    fun testWildcardDnsCannotAcceptMalformedHostnames() {
        for (host in listOf("bad_name", "invalid_host_name_!@#", "-host", "host-", "a..b", "a/b", "a@b", "localhost:29170", "a".repeat(64),
            List(5) { "a".repeat(60) }.joinToString("."))) {
            var lookupCalled = false
            val valid = McpSettingsConfigurable.isValidHost(host) {
                lookupCalled = true
                InetAddress.getLoopbackAddress()
            }
            assertFalse(host, valid)
            assertFalse("Reject syntax before calling the resolver: $host", lookupCalled)
        }
    }

    fun testValidNamesAreNormalizedForTheResolver() {
        for ((host, expected) in listOf(" mcp-host.example " to "mcp-host.example", "localhost." to "localhost.",
            "пример.рф" to "xn--e1afmkfd.xn--p1ai")) {
            var resolved: String? = null
            assertTrue(host, McpSettingsConfigurable.isValidHost(host) {
                resolved = it
                InetAddress.getLoopbackAddress()
            })
            assertEquals(expected, resolved)
        }
    }

    fun testUnresolvableValidHostnameIsRejected() {
        assertFalse(McpSettingsConfigurable.isValidHost("missing.example") { throw UnknownHostException(it) })
    }

    fun testUnicodeNumericAddressesKeepStrictIpv4Validation() {
        assertTrue(McpSettingsConfigurable.isValidHost("１２７．０．０．１"))
        for (host in listOf("１２７．１", "２５６．０．０．１", "１．２．３．４．５")) {
            var lookupCalled = false
            val valid = McpSettingsConfigurable.isValidHost(host) {
                lookupCalled = true
                InetAddress.getLoopbackAddress()
            }
            assertFalse("IDN conversion must not bypass IPv4 syntax: $host", valid)
            assertFalse("Reject malformed numeric addresses before DNS lookup: $host", lookupCalled)
        }
    }
}
