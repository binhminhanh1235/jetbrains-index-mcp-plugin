package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBTextField
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import javax.swing.JSpinner

/**
 * Covers apply-time validation traps in [McpSettingsConfigurable]:
 *
 * 1. `isHostValidationPending` is set on every keystroke but was only cleared by the async
 *    focus-lost validation, so a keyboard-driven Apply (Enter while the host field has focus)
 *    was rejected forever. apply() must instead validate the host synchronously.
 * 2. The port-availability bind check used to run even when host/port were unchanged, so an
 *    externally occupied port blocked applying unrelated settings.
 * 3. IDN validation normalized DNS names but apply persisted and bound the original Unicode
 *    input, which the JVM socket resolver could not resolve.
 */
class McpSettingsConfigurableApplyTest : McpPlatformTestCase() {

    private var originalHost: String = ""
    private var originalPort: Int = 0
    private var originalMaxHistorySize: Int = 0
    private val requestedRestarts = mutableListOf<Pair<String, Int>>()

    override fun setUp() {
        super.setUp()
        val settings = McpSettings.getInstance()
        originalHost = settings.serverHost
        originalPort = settings.serverPort
        originalMaxHistorySize = settings.maxHistorySize
    }

    override fun tearDown() {
        try {
            val settings = McpSettings.getInstance()
            settings.serverHost = originalHost
            settings.serverPort = originalPort
            settings.maxHistorySize = originalMaxHistorySize
        } finally {
            super.tearDown()
        }
    }

    fun testApplySucceedsWhileHostValidationIsPendingForValidHost() {
        withConfigurable { configurable ->
            val hostField = hostField(configurable)

            // Typing marks validation as pending; only losing focus would clear it, and focus
            // never leaves the field when the dialog is confirmed from the keyboard. The
            // trailing space is trimmed by apply(), so the effective address stays unchanged.
            hostField.text = McpSettings.getInstance().serverHost + " "
            assertTrue(
                "Editing the host field must mark validation as pending",
                isHostValidationPending(configurable)
            )

            configurable.apply()

            assertFalse(
                "Synchronous validation during apply must clear the pending flag",
                isHostValidationPending(configurable)
            )
        }
    }

    fun testApplyWhileHostValidationIsPendingRejectsInvalidHost() {
        withConfigurable { configurable ->
            val hostField = hostField(configurable)

            hostField.text = "999.999.999.999"
            assertTrue(isHostValidationPending(configurable))

            try {
                configurable.apply()
                fail("apply() must reject an invalid host even while async validation is pending")
            } catch (e: ConfigurationException) {
                assertTrue(
                    "Error must report the invalid host, not a pending validation: ${e.messageHtml}",
                    e.messageHtml.toString().contains("999.999.999.999")
                )
            }

            assertEquals(
                "An invalid host must not be persisted",
                originalHost,
                McpSettings.getInstance().serverHost
            )
        }
    }

    fun testApplyWithUnchangedAddressSucceedsWhilePortIsOccupiedExternally() {
        val settings = McpSettings.getInstance()
        ServerSocket().use { externalListener ->
            externalListener.reuseAddress = true
            externalListener.bind(InetSocketAddress(settings.serverHost, 0))
            settings.serverPort = externalListener.localPort

            withConfigurable { configurable ->
                maxHistorySizeSpinner(configurable).value = 275

                // Host and port are unchanged, so the bind check must be skipped even though
                // the configured address is currently held by another process.
                configurable.apply()
            }

            assertEquals(
                "Unrelated settings must apply while the unchanged port is externally occupied",
                275,
                settings.maxHistorySize
            )
        }
    }

    fun testLegacyPersistedHostsDoNotBlockUnrelatedSettings() {
        val settings = McpSettings.getInstance()
        val legacyHosts = listOf("my_host", "a".repeat(64), "-legacy-host", "legacy-host-")

        for ((index, savedHost) in legacyHosts.withIndex()) {
            settings.serverHost = savedHost
            val newHistorySize = 300 + index
            requestedRestarts.clear()

            withConfigurable { configurable ->
                assertFalse("An unchanged legacy host must not mark Settings modified", configurable.isModified())
                maxHistorySizeSpinner(configurable).value = newHistorySize

                configurable.apply()

                assertEquals(savedHost, settings.serverHost)
                assertEquals(newHistorySize, settings.maxHistorySize)
                assertTrue("An unchanged legacy host must not restart the server", requestedRestarts.isEmpty())
                assertFalse(configurable.isModified())
            }
        }
    }

    fun testApplyPersistsNormalizedUnicodeHostThatCanBeBound() {
        val settings = McpSettings.getInstance()
        // An already persisted Unicode host skips the unchanged-address bind check. Apply
        // must still normalize it so the saved setting is usable on the next server start.
        settings.serverHost = "ｌｏｃａｌｈｏｓｔ"
        settings.serverPort = ServerSocket(0, 1, InetAddress.getByName("localhost")).use { it.localPort }

        withConfigurable { configurable ->
            hostField(configurable).text = " ${settings.serverHost} "

            configurable.apply()

            assertEquals("Persist the same DNS name that passed validation", "localhost", settings.serverHost)
            assertEquals(
                "The restart must use the same normalized host and configured port",
                listOf("localhost" to settings.serverPort),
                requestedRestarts
            )
            val bindAddress = InetSocketAddress(settings.serverHost, 0)
            assertFalse("The persisted bind host must resolve without another IDN conversion", bindAddress.isUnresolved)
            ServerSocket().use { listener ->
                listener.bind(bindAddress)
                assertTrue("The persisted host must support an actual socket bind", listener.isBound)
            }
            assertFalse("A successful Apply must leave the host field unmodified", configurable.isModified())
        }
    }

    fun testApplyNormalizesChangedUnicodeHostBeforeBindCheck() {
        val settings = McpSettings.getInstance()
        settings.serverHost = "127.0.0.1"
        settings.serverPort = ServerSocket(0, 1, InetAddress.getByName("localhost")).use { it.localPort }

        withConfigurable { configurable ->
            hostField(configurable).text = "ｌｏｃａｌｈｏｓｔ"

            configurable.apply()

            assertEquals("The validated DNS name must also be used by the bind check", "localhost", settings.serverHost)
            assertEquals(listOf("localhost" to settings.serverPort), requestedRestarts)
        }
    }

    fun testApplyUnicodeEquivalentHostSkipsUnchangedAddressBindCheck() {
        val settings = McpSettings.getInstance()
        settings.serverHost = "localhost"
        ServerSocket(0, 1, InetAddress.getByName(settings.serverHost)).use { externalListener ->
            settings.serverPort = externalListener.localPort

            withConfigurable { configurable ->
                hostField(configurable).text = "ｌｏｃａｌｈｏｓｔ"
                maxHistorySizeSpinner(configurable).value = 275

                configurable.apply()

                assertEquals("localhost", settings.serverHost)
                assertEquals("Equivalent host spelling must not block unrelated settings", 275, settings.maxHistorySize)
                assertTrue("Equivalent host spelling must not restart the server", requestedRestarts.isEmpty())
                assertFalse("A successful Apply must leave the host field unmodified", configurable.isModified())
            }
        }
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

    private fun hostField(configurable: McpSettingsConfigurable): JBTextField =
        readField(configurable, "serverHostField")

    private fun maxHistorySizeSpinner(configurable: McpSettingsConfigurable): JSpinner =
        readField(configurable, "maxHistorySizeSpinner")

    private fun isHostValidationPending(configurable: McpSettingsConfigurable): Boolean =
        readField(configurable, "isHostValidationPending")

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(configurable: McpSettingsConfigurable, name: String): T {
        val field = McpSettingsConfigurable::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(configurable) as T
    }
}
