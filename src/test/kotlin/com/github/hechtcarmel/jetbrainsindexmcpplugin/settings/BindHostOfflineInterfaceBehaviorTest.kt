package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBTextField
import java.net.NetworkInterface
import javax.swing.JSpinner

/** Saved scoped IPv6 addresses must not block unrelated settings while their interface is offline. */
class BindHostOfflineInterfaceBehaviorTest : McpPlatformTestCase() {
    private lateinit var originalHost: String
    private var originalHistorySize = 0
    private val requestedRestarts = mutableListOf<Pair<String, Int>>()

    override fun setUp() {
        super.setUp()
        val settings = McpSettings.getInstance()
        originalHost = settings.serverHost
        originalHistorySize = settings.maxHistorySize
    }

    override fun tearDown() {
        try {
            val settings = McpSettings.getInstance()
            settings.serverHost = originalHost
            settings.maxHistorySize = originalHistorySize
        } finally {
            super.tearDown()
        }
    }

    fun testResetDoesNotMarkUnchangedOfflineScopedHostModified() {
        val settings = McpSettings.getInstance()
        settings.serverHost = offlineScopedHost()

        withConfigurable { configurable ->
            assertEquals(settings.serverHost, field<JBTextField>(configurable, "serverHostField").text)
            assertFalse("Reset must not depend on whether the saved interface is online", configurable.isModified())
        }
    }

    fun testUnrelatedSettingCanApplyWhileUnchangedScopedInterfaceIsOffline() {
        val settings = McpSettings.getInstance()
        val savedHost = offlineScopedHost()
        val savedPort = settings.serverPort
        val newHistorySize = if (originalHistorySize == 275) 276 else 275
        settings.serverHost = savedHost

        withConfigurable { configurable ->
            field<JSpinner>(configurable, "maxHistorySizeSpinner").value = newHistorySize
            assertTrue(configurable.isModified())

            configurable.apply()

            assertEquals(newHistorySize, settings.maxHistorySize)
            assertEquals(savedHost, settings.serverHost)
            assertEquals(savedPort, settings.serverPort)
            assertTrue("An unchanged address must not restart the server", requestedRestarts.isEmpty())
            assertFalse("Successful Apply must leave the settings unmodified", configurable.isModified())
        }
    }

    fun testChangingToOfflineScopedHostStillFailsValidation() {
        val settings = McpSettings.getInstance()
        settings.serverHost = "127.0.0.1"
        val newHost = offlineScopedHost()
        val newHistorySize = if (originalHistorySize == 275) 276 else 275

        withConfigurable { configurable ->
            field<JBTextField>(configurable, "serverHostField").text = newHost
            field<JSpinner>(configurable, "maxHistorySizeSpinner").value = newHistorySize

            try {
                configurable.apply()
                fail("A newly entered scoped address must still require an available interface")
            } catch (e: ConfigurationException) {
                assertTrue(e.messageHtml.toString().contains(newHost))
            }

            assertEquals("127.0.0.1", settings.serverHost)
            assertEquals("Validation must precede all settings writes", originalHistorySize, settings.maxHistorySize)
            assertTrue(requestedRestarts.isEmpty())
        }
    }

    private fun offlineScopedHost(): String {
        // Model a saved VPN interface that has since disappeared without changing the machine's network.
        val absentInterface = generateSequence(0) { it + 1 }
            .map { "mcp-test$it" }
            .first { NetworkInterface.getByName(it) == null }
        return "fe80::1%$absentInterface"
    }

    private fun withConfigurable(block: (McpSettingsConfigurable) -> Unit) {
        val configurable = McpSettingsConfigurable { host, port -> requestedRestarts += host to port }
        try {
            configurable.createComponent()
            configurable.reset()
            block(configurable)
        } finally {
            configurable.disposeUIResources()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(configurable: McpSettingsConfigurable, name: String): T =
        McpSettingsConfigurable::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(configurable) as T
        }
}
