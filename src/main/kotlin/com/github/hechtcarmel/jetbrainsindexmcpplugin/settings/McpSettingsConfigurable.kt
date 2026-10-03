package com.github.hechtcarmel.jetbrainsindexmcpplugin.settings

import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpBundle
import com.github.hechtcarmel.jetbrainsindexmcpplugin.McpConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectModeService
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.transport.KtorMcpServer
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.McpServerService
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import org.jetbrains.annotations.VisibleForTesting
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.net.IDN
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.function.Supplier
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import javax.swing.event.DocumentEvent

class McpSettingsConfigurable internal constructor(
    private val restartServer: (String, Int) -> Unit
) : Configurable {

    constructor() : this(::restartServerAsync)

    private var panel: JPanel? = null
    private var serverHostField: JBTextField? = null
    private var maxHistorySizeSpinner: JSpinner? = null
    private var serverPortSpinner: JSpinner? = null
    private var syncExternalChangesCheckBox: JBCheckBox? = null
    private var availableProjectsModeComboBox: ComboBox<McpSettings.AvailableProjectsMode>? = null
    private var responseFormatComboBox: ComboBox<McpSettings.ResponseFormat>? = null
    private var uiDisposable: Disposable? = null

    // Lifecycle UI fields
    private var lifecycleEnabledCheckBox: JBCheckBox? = null
    private var focusToBackgroundSpinner: JSpinner? = null
    private var backgroundToDormantSpinner: JSpinner? = null
    private var dormantToClosedSpinner: JSpinner? = null
    private var lifecycleLogBufferSizeSpinner: JSpinner? = null
    private var lifecycleLogToFileCheckBox: JBCheckBox? = null
    private var minimumOpenProjectsSpinner: JSpinner? = null
    private var managedProjectsContent: JPanel? = null

    private var lastHostValidation: ValidationInfo? = null
    private var hostValidationErrorLabel: JBLabel? = null
    private var hostValidationIcon: AsyncProcessIcon? = null
    private var hostValidIcon: JBLabel? = null
    private var hostWarningLabel: JBLabel? = null
    private var isHostValidationPending = false

    override fun getDisplayName(): String = McpBundle.message("settings.title")

    override fun createComponent(): JComponent {
        uiDisposable = Disposer.newDisposable()
        serverHostField = JBTextField(McpConstants.DEFAULT_SERVER_HOST, 25).apply {
            toolTipText = McpBundle.message("settings.serverHost.tooltip")
        }
        hostValidationErrorLabel = JBLabel().apply {
            foreground = JBColor.RED
            isVisible = false
        }
        hostValidationIcon = AsyncProcessIcon("HostValidation").apply {
            isVisible = false
        }
        hostValidIcon = JBLabel(AllIcons.General.InspectionsOK).apply {
            isVisible = false
        }
        hostWarningLabel = JBLabel(McpBundle.message("settings.serverHost.publicWarning")).apply {
            foreground = JBColor.RED
            isVisible = false
            border = JBUI.Borders.emptyBottom(5)
        }

        val serverHostInputRow = JPanel(HorizontalLayout(JBUI.scale(5))).apply {
            add(serverHostField)
            add(hostValidationIcon)
            add(hostValidIcon)
            add(hostValidationErrorLabel)
        }

        installHostValidator(serverHostField!!)

        maxHistorySizeSpinner = JSpinner(SpinnerNumberModel(100, 10, 10000, 10))
        serverPortSpinner = JSpinner(SpinnerNumberModel(McpConstants.getDefaultServerPort(), 1024, 65535, 1)).apply {
            toolTipText = McpBundle.message("settings.serverPort.tooltip")
        }
        syncExternalChangesCheckBox = JBCheckBox(McpBundle.message("settings.syncExternalChanges")).apply {
            toolTipText = McpBundle.message("settings.syncExternalChanges.tooltip")
        }
        availableProjectsModeComboBox = ComboBox(McpSettings.AvailableProjectsMode.values()).apply {
            toolTipText = McpBundle.message("settings.availableProjectsMode.tooltip")
            renderer = SimpleListCellRenderer.create("") { value ->
                value?.let(::availableProjectsModeLabel).orEmpty()
            }
        }
        responseFormatComboBox = ComboBox(McpSettings.ResponseFormat.values()).apply {
            toolTipText = McpBundle.message("settings.responseFormat.tooltip")
            renderer = SimpleListCellRenderer.create("") { value ->
                value?.let(::responseFormatLabel).orEmpty()
            }
        }

        val warningLabel = JBLabel(McpBundle.message("settings.syncExternalChanges.warning")).apply {
            foreground = JBColor.RED
        }

        val syncPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            val checkboxRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                add(syncExternalChangesCheckBox)
            }
            add(checkboxRow)
            val warningRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(24), 0)).apply {
                add(warningLabel)
            }
            add(warningRow)
        }

        val lifecyclePanel = createLifecyclePanel()

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(McpBundle.message("settings.serverHost") + ":"), serverHostInputRow, 1, false)
            .addComponentToRightColumn(hostWarningLabel!!)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.serverPort") + ":"), serverPortSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.maxHistorySize") + ":"), maxHistorySizeSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.availableProjectsMode") + ":"), availableProjectsModeComboBox!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("settings.responseFormat") + ":"), responseFormatComboBox!!, 1, false)
            .addComponent(syncPanel, 1)
            .addSeparator(10)
            .addComponent(JBLabel(McpBundle.message("lifecycle.section.title")), 5)
            .addComponent(lifecyclePanel, 1)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    private fun createLifecyclePanel(): JComponent {
        val settings = McpSettings.getInstance()
        lifecycleEnabledCheckBox = JBCheckBox(McpBundle.message("lifecycle.enabled.label"), settings.lifecycleEnabled)
        focusToBackgroundSpinner = JSpinner(SpinnerNumberModel(settings.focusToBackgroundMinutes, 1, 60, 1))
        backgroundToDormantSpinner = JSpinner(SpinnerNumberModel(settings.backgroundToDormantMinutes, 1, 60, 1))
        dormantToClosedSpinner = JSpinner(SpinnerNumberModel(settings.dormantToClosedMinutes, 1, 120, 1))
        lifecycleLogBufferSizeSpinner = JSpinner(SpinnerNumberModel(settings.lifecycleLogBufferSize, 100, 10000, 100))
        lifecycleLogToFileCheckBox = JBCheckBox(McpBundle.message("lifecycle.logToFile.label"), settings.lifecycleLogToFile)
        minimumOpenProjectsSpinner = JSpinner(SpinnerNumberModel(settings.minimumOpenProjects, 1, 20, 1))

        return FormBuilder.createFormBuilder()
            .addComponent(lifecycleEnabledCheckBox!!)
            .addLabeledComponent(JBLabel(McpBundle.message("lifecycle.focusToBackground.label")), focusToBackgroundSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("lifecycle.backgroundToDormant.label")), backgroundToDormantSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("lifecycle.dormantToClosed.label")), dormantToClosedSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("lifecycle.minimumOpenProjects.label")), minimumOpenProjectsSpinner!!, 1, false)
            .addLabeledComponent(JBLabel(McpBundle.message("lifecycle.logBufferSize.label")), lifecycleLogBufferSizeSpinner!!, 1, false)
            .addComponent(lifecycleLogToFileCheckBox!!)
            .addComponent(createManagedProjectsPanel())
            .panel
    }

    private fun createManagedProjectsPanel(): JComponent {
        val outer = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        outer.add(JBLabel(McpBundle.message("lifecycle.managed.label")).apply {
            border = JBUI.Borders.emptyTop(8)
        })

        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull()
        if (modeService == null) {
            outer.add(JBLabel(McpBundle.message("lifecycle.managed.unavailable")).apply { foreground = JBColor.GRAY })
            return outer
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            val enrollAll = com.intellij.ui.components.JBLabel(McpBundle.message("lifecycle.enrollAll.button")).apply {
                foreground = JBColor.BLUE
                cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(0, 0, 0, 8)
            }
            enrollAll.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    val open = com.intellij.openapi.project.ProjectManager.getInstance().openProjects.toList()
                    modeService.enrollAll(open)
                    refreshManagedProjectsPanel()
                }
            })
            val releaseAll = com.intellij.ui.components.JBLabel(McpBundle.message("lifecycle.releaseAll.button")).apply {
                foreground = JBColor.RED
                cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            }
            releaseAll.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    modeService.releaseAll()
                    refreshManagedProjectsPanel()
                }
            })
            add(enrollAll)
            add(releaseAll)
        }
        outer.add(buttonRow)

        val content = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        managedProjectsContent = content
        refreshManagedProjectsPanel()

        val scroll = javax.swing.JScrollPane(content).apply {
            border = javax.swing.BorderFactory.createEmptyBorder()
            preferredSize = java.awt.Dimension(preferredSize.width, 150)
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        outer.add(scroll)
        return outer
    }

    private fun refreshManagedProjectsPanel() {
        val content = managedProjectsContent ?: return
        val modeService = runCatching { ProjectModeService.getInstance() }.getOrNull() ?: return

        content.removeAll()

        val openProjects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
            .filter { !it.isDefault }
            .associateBy { it.basePath ?: "" }
        val managedModes = modeService.getAllManagedModes()

        val allPaths = (openProjects.keys + managedModes.keys).filter { it.isNotEmpty() }.toSortedSet()

        if (allPaths.isEmpty()) {
            content.add(JBLabel(McpBundle.message("lifecycle.managed.none")).apply { foreground = JBColor.GRAY })
        } else {
            for (path in allPaths) {
                val openProject = openProjects[path]
                val mode = managedModes[path]
                val isManaged = mode != null
                val isClosed = mode == com.github.hechtcarmel.jetbrainsindexmcpplugin.lifecycle.ProjectMode.CLOSED
                val name = openProject?.name ?: path.substringAfterLast("/")

                val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1))

                val checkbox = JBCheckBox("", isManaged).apply {
                    isEnabled = openProject != null || isManaged
                    addActionListener {
                        if (isSelected) {
                            if (openProject != null) modeService.enroll(openProject)
                        } else {
                            modeService.release(path)
                        }
                        refreshManagedProjectsPanel()
                    }
                }
                row.add(checkbox)

                val nameLabel = JBLabel(name).apply {
                    if (isClosed || openProject == null) foreground = JBColor.GRAY
                    toolTipText = path
                }
                row.add(nameLabel)

                val modeText = when {
                    mode != null -> "  [${mode.name.lowercase()}]"
                    else -> "  [not managed]"
                }
                row.add(JBLabel(modeText).apply { foreground = JBColor.GRAY })

                if (isManaged) {
                    val xBtn = com.intellij.ui.components.JBLabel("✕").apply {
                        foreground = JBColor.RED
                        cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                        toolTipText = "Release from lifecycle management"
                        border = JBUI.Borders.empty(0, 4, 0, 0)
                    }
                    xBtn.addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseClicked(e: java.awt.event.MouseEvent) {
                            modeService.release(path)
                            refreshManagedProjectsPanel()
                        }
                    })
                    row.add(xBtn)
                }

                content.add(row)
            }
        }

        content.revalidate()
        content.repaint()
    }

    override fun isModified(): Boolean {
        val settings = McpSettings.getInstance()
        val normalizedHost = serverHostField?.text?.let { normalizeBindHostForSettings(it, settings.serverHost) }

        if (normalizedHost != settings.serverHost ||
            serverPortSpinner?.value != settings.serverPort ||
            maxHistorySizeSpinner?.value != settings.maxHistorySize ||
            syncExternalChangesCheckBox?.isSelected != settings.syncExternalChanges ||
            availableProjectsModeComboBox?.selectedItem != settings.availableProjectsMode ||
            responseFormatComboBox?.selectedItem != settings.responseFormat ||
            lifecycleEnabledCheckBox?.isSelected != settings.lifecycleEnabled ||
            focusToBackgroundSpinner?.value != settings.focusToBackgroundMinutes ||
            backgroundToDormantSpinner?.value != settings.backgroundToDormantMinutes ||
            dormantToClosedSpinner?.value != settings.dormantToClosedMinutes ||
            lifecycleLogBufferSizeSpinner?.value != settings.lifecycleLogBufferSize ||
            lifecycleLogToFileCheckBox?.isSelected != settings.lifecycleLogToFile ||
            minimumOpenProjectsSpinner?.value != settings.minimumOpenProjects) {
            return true
        }

        return false
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val settings = McpSettings.getInstance()
        val oldHost = settings.serverHost
        val oldPort = settings.serverPort
        val hostInput = serverHostField?.text?.trim() ?: McpConstants.DEFAULT_SERVER_HOST
        val newPort = serverPortSpinner?.value as? Int ?: McpConstants.getDefaultServerPort()

        if (hostInput.isEmpty()) {
            throw ConfigurationException(
                McpBundle.message("settings.serverHost.empty"),
                McpBundle.message("settings.validation.host.title")
            )
        }
        // Persist and bind the same representation that DNS validation resolves. In
        // particular, JVM socket APIs do not perform IDN conversion on Unicode hostnames.
        val newHost = normalizeBindHostForSettings(hostInput, oldHost) ?: throw ConfigurationException(
            McpBundle.message("settings.serverHost.invalid", hostInput),
            McpBundle.message("settings.validation.host.title")
        )

        val hostChanged = newHost != oldHost

        if (!hostChanged) {
            // Applying an equivalent spelling, or an unchanged legacy value, completes the
            // current edit without re-validating it. Do not leave stale async UI state behind.
            isHostValidationPending = false
            lastHostValidation = null
            hostValidationErrorLabel?.isVisible = false
            hostValidationIcon?.isVisible = false
            hostValidIcon?.isVisible = false
            serverHostField?.let { field ->
                ComponentValidator.getInstance(field).ifPresent { it.updateInfo(null) }
            }
        }

        // The async validation only runs on focus loss, which never happens when the user
        // confirms the dialog from the keyboard while the host field still has focus. Instead
        // of rejecting the apply until focus moves, validate a changed host synchronously.
        // Unchanged legacy values are grandfathered: tightening validation must not make the
        // Settings page impossible to apply for an unrelated change.
        if (hostChanged && isHostValidationPending) {
            if (!isValidHost(newHost)) {
                throw ConfigurationException(
                    McpBundle.message("settings.serverHost.invalid", hostInput),
                    McpBundle.message("settings.validation.host.title")
                )
            }
            isHostValidationPending = false
            lastHostValidation = null
        }

        if (hostChanged && lastHostValidation != null) {
            throw ConfigurationException(
                McpBundle.message("settings.serverHost.invalid", hostInput),
                McpBundle.message("settings.validation.host.title")
            )
        }

        // Bind-test the address only when it actually changed. When it is unchanged, an
        // externally occupied port (the exact error state whose notification points here)
        // must not block applying unrelated settings such as tool toggles.
        if ((newHost != oldHost || newPort != oldPort) && !isServerAddressAvailable(newHost, newPort)) {
            throw ConfigurationException(
                McpBundle.message("settings.serverAddress.unavailable", "$newHost:$newPort"),
                McpBundle.message("settings.validation.serverAddress.title")
            )
        }

        settings.serverHost = newHost
        settings.serverPort = newPort
        settings.maxHistorySize = maxHistorySizeSpinner?.value as? Int ?: 100
        settings.syncExternalChanges = syncExternalChangesCheckBox?.isSelected ?: false
        settings.availableProjectsMode =
            availableProjectsModeComboBox?.selectedItem as? McpSettings.AvailableProjectsMode
                ?: McpSettings.AvailableProjectsMode.EXPANDED
        settings.responseFormat =
            responseFormatComboBox?.selectedItem as? McpSettings.ResponseFormat
                ?: McpSettings.ResponseFormat.JSON

        settings.lifecycleEnabled = lifecycleEnabledCheckBox?.isSelected ?: true
        settings.focusToBackgroundMinutes = focusToBackgroundSpinner?.value as? Int ?: 2
        settings.backgroundToDormantMinutes = backgroundToDormantSpinner?.value as? Int ?: 2
        settings.dormantToClosedMinutes = dormantToClosedSpinner?.value as? Int ?: 10
        settings.lifecycleLogBufferSize = lifecycleLogBufferSizeSpinner?.value as? Int ?: 500
        settings.lifecycleLogToFile = lifecycleLogToFileCheckBox?.isSelected ?: false
        settings.minimumOpenProjects = minimumOpenProjectsSpinner?.value as? Int ?: 4

        if (newHost != oldHost || newPort != oldPort) {
            restartServer(newHost, newPort)
        }
    }

    /**
     * Checks if a host:port is available for binding.
     * Returns true if we can bind to the address, false if it's in use.
     */
    private fun isServerAddressAvailable(host: String, port: Int): Boolean {
        val mcpService = McpServerService.getInstance()
        val currentPort = McpSettings.getInstance().serverPort

        // If the port matches our current server's port and it's running, we consider it available.
        // We skip the bind check here because our own server is already occupying the port,
        // which would cause a false "address in use" error (especially when switching
        // between 0.0.0.0 and 127.0.0.1). We trust that we will stop our server
        // before binding to the new address during restart.
        if (port == currentPort && mcpService.isInitialized && mcpService.isServerRunning()) {
            return true
        }

        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        val settings = McpSettings.getInstance()
        serverHostField?.text = settings.serverHost
        serverPortSpinner?.value = settings.serverPort
        maxHistorySizeSpinner?.value = settings.maxHistorySize
        syncExternalChangesCheckBox?.isSelected = settings.syncExternalChanges
        availableProjectsModeComboBox?.selectedItem = settings.availableProjectsMode
        responseFormatComboBox?.selectedItem = settings.responseFormat

        hostValidationErrorLabel?.isVisible = false
        hostValidationIcon?.isVisible = false
        hostValidIcon?.isVisible = false
        updateHostWarning(settings.serverHost)
        isHostValidationPending = false
        lastHostValidation = null

        lifecycleEnabledCheckBox?.isSelected = settings.lifecycleEnabled
        focusToBackgroundSpinner?.value = settings.focusToBackgroundMinutes
        backgroundToDormantSpinner?.value = settings.backgroundToDormantMinutes
        dormantToClosedSpinner?.value = settings.dormantToClosedMinutes
        lifecycleLogBufferSizeSpinner?.value = settings.lifecycleLogBufferSize
        lifecycleLogToFileCheckBox?.isSelected = settings.lifecycleLogToFile
        minimumOpenProjectsSpinner?.value = settings.minimumOpenProjects
    }

    private fun updateHostWarning(host: String) {
        val trimmedHost = host.trim()
        val isDefault = trimmedHost == McpConstants.DEFAULT_SERVER_HOST
        hostWarningLabel?.isVisible = !isDefault
    }

    private fun installHostValidator(field: JBTextField) {
        // We use an empty validation info initially so we can control when it appears
        val validator = ComponentValidator(uiDisposable!!).withValidator {
            lastHostValidation
        }.installOn(field)

        field.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                // Clear validation state immediately on typing.
                // Host validation runs only after focus is lost.
                hostValidationErrorLabel?.isVisible = false
                hostValidIcon?.isVisible = false
                hostValidationIcon?.isVisible = false
                isHostValidationPending = true
                lastHostValidation = null

                // Update warning visibility
                updateHostWarning(field.text)

                ComponentValidator.getInstance(field).ifPresent { it.updateInfo(lastHostValidation) }
            }
        })

        field.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                val host = field.text.trim()

                isHostValidationPending = true
                hostValidationErrorLabel?.isVisible = false
                hostValidIcon?.isVisible = false
                hostValidationIcon?.isVisible = true

                ApplicationManager.getApplication().executeOnPooledThread {
                    val isValid = isValidHost(host)
                    val labelMessage = if (host.isEmpty())
                        McpBundle.message("settings.serverHost.empty")
                    else
                        McpBundle.message("settings.serverHost.invalidShort")

                    // Use empty message for ComponentValidator to show red border but avoid tooltip popup
                    // as we are showing the error message in the label next to the input
                    val info = if (isValid) null else ValidationInfo("", field)

                    ApplicationManager.getApplication().invokeLater({
                        // Only update UI if the text hasn't changed since we started
                        if (field.text.trim() == host) {
                            hostValidationIcon?.isVisible = false
                            isHostValidationPending = false
                            lastHostValidation = info

                            ComponentValidator.getInstance(field).ifPresent { it.updateInfo(info) }

                            if (!isValid) {
                                hostValidationErrorLabel?.text = labelMessage
                                hostValidationErrorLabel?.isVisible = true
                                hostValidIcon?.isVisible = false
                            } else {
                                hostValidationErrorLabel?.isVisible = false
                                hostValidIcon?.isVisible = true
                            }
                        }
                    }, ModalityState.any())
                }
            }
        })
    }

    override fun disposeUIResources() {
        panel = null
        serverHostField = null
        hostValidationErrorLabel = null
        hostValidationIcon = null
        hostValidIcon = null
        serverPortSpinner = null
        maxHistorySizeSpinner = null
        syncExternalChangesCheckBox = null
        availableProjectsModeComboBox = null
        responseFormatComboBox = null
        lifecycleEnabledCheckBox = null
        focusToBackgroundSpinner = null
        backgroundToDormantSpinner = null
        dormantToClosedSpinner = null
        lifecycleLogBufferSizeSpinner = null
        lifecycleLogToFileCheckBox = null
        minimumOpenProjectsSpinner = null
        managedProjectsContent = null
        uiDisposable?.let { Disposer.dispose(it) }
        uiDisposable = null
    }

    private fun availableProjectsModeLabel(mode: McpSettings.AvailableProjectsMode): String =
        when (mode) {
            McpSettings.AvailableProjectsMode.EXPANDED -> McpBundle.message("settings.availableProjectsMode.expanded")
            McpSettings.AvailableProjectsMode.COMPACT -> McpBundle.message("settings.availableProjectsMode.compact")
        }

    private fun responseFormatLabel(format: McpSettings.ResponseFormat): String =
        when (format) {
            McpSettings.ResponseFormat.JSON -> McpBundle.message("settings.responseFormat.json")
            McpSettings.ResponseFormat.TOON -> McpBundle.message("settings.responseFormat.toon")
        }

    companion object {
        private fun restartServerAsync(newHost: String, newPort: Int) {
            // EmbeddedServer.stop() blocks while in-flight MCP calls drain — calls that may
            // themselves be waiting for the EDT — and the CIO bind is blocking too. Keep
            // the restart on a pooled thread; only the result notification goes to the EDT.
            ApplicationManager.getApplication().executeOnPooledThread {
                val mcpService = McpServerService.getInstance()
                if (!mcpService.isInitialized) return@executeOnPooledThread
                val result = mcpService.restartServer(newHost, newPort)
                ApplicationManager.getApplication().invokeLater({
                    when (result) {
                        is KtorMcpServer.StartResult.Success -> {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                                .createNotification(
                                    McpBundle.message("notification.serverRestarted.title"),
                                    McpBundle.message("notification.serverRestarted", "$newHost:$newPort"),
                                    NotificationType.INFORMATION
                                )
                                .notify(null)
                        }
                        is KtorMcpServer.StartResult.PortInUse -> {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                                .createNotification(
                                    McpBundle.message("notification.serverStartFailed.title"),
                                    McpBundle.message("notification.serverPortInUse.content", result.port, newHost),
                                    NotificationType.ERROR
                                )
                                .notify(null)
                        }
                        is KtorMcpServer.StartResult.Error -> {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup(McpConstants.NOTIFICATION_GROUP_ID)
                                .createNotification(
                                    McpBundle.message("notification.serverStartFailed.title"),
                                    McpBundle.message("notification.serverStartFailed.content", result.message),
                                    NotificationType.ERROR
                                )
                                .notify(null)
                        }
                    }
                }, ModalityState.any())
            }
        }

        private val IPV4_PATTERN = Regex("^[0-9.]+\$")
        private val HOSTNAME_LABEL_PATTERN = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\$")
        private val IPV6_HEXTET_PATTERN = Regex("^[0-9A-Fa-f]{1,4}\$")
        private val IPV6_ZONE_PATTERN = Regex("^[A-Za-z0-9._~-]+\$")

        @VisibleForTesting
        fun isValidIpv4(host: String): Boolean {
            if (!IPV4_PATTERN.matches(host)) return false
            val parts = host.split(".")
            // Check for exactly 4 non-empty parts with valid octet values (0-255)
            return parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        @VisibleForTesting
        fun isValidHost(host: String): Boolean = isValidHost(host, InetAddress::getByName)

        internal fun isValidHost(host: String, resolveHost: (String) -> InetAddress): Boolean {
            val bindHost = normalizeBindHost(host) ?: return false
            if (IPV4_PATTERN.matches(bindHost)) return true
            return runCatching { resolveHost(bindHost) }.isSuccess
        }

        /**
         * Preserve an unchanged IPv6 literal when its named interface is temporarily absent.
         * Parsing a named scope asks the JVM for a live network interface, which can fail after
         * a VPN disconnect. Hostnames still need IDN normalization, even when already persisted.
         */
        private fun normalizeBindHostForSettings(host: String, savedHost: String): String? {
            val trimmedHost = host.trim()
            return normalizeBindHost(trimmedHost)
                ?: trimmedHost.takeIf { it == savedHost }
        }

        /** Validates host syntax and returns the representation used by JVM socket APIs. */
        private fun normalizeBindHost(host: String): String? {
            val trimmedHost = host.trim()
            if (trimmedHost.isEmpty()) return null

            // Check if input consists only of numbers and dots (potential IPv4)
            if (IPV4_PATTERN.matches(trimmedHost)) {
                return trimmedHost.takeIf(::isValidIpv4)
            }

            // InetAddress delegates to the system resolver, which may be configured with a
            // wildcard DNS suffix and report even syntactically invalid input as resolvable.
            // Validate IPv6/hostname syntax first so resolution cannot turn values containing
            // underscores or URI punctuation into accepted bind addresses.
            if (trimmedHost.contains(':') || trimmedHost.startsWith('[') || trimmedHost.endsWith(']')) {
                return trimmedHost.takeIf(::isValidIpv6Literal)
            }
            val asciiHost = runCatching { IDN.toASCII(trimmedHost.removeSuffix("."), IDN.USE_STD3_ASCII_RULES) }
                .getOrNull()
                ?: return null
            if (asciiHost.isEmpty() || asciiHost.length > 253 ||
                asciiHost.split('.').any { !HOSTNAME_LABEL_PATTERN.matches(it) }
            ) {
                return null
            }

            val absoluteSuffix = if (trimmedHost.endsWith('.')) "." else ""
            val bindHost = asciiHost + absoluteSuffix
            // IDN also maps fullwidth digits and dots; keep strict IPv4 rules after that
            // conversion instead of accepting JVM shorthand such as 127.1.
            if (IPV4_PATTERN.matches(bindHost) && !isValidIpv4(bindHost)) return null
            return bindHost
        }

        /**
         * Validates an IPv6 literal without consulting DNS. Resolution is deliberately kept in
         * [isValidHost], after this syntax gate, so values such as `localhost:29170` cannot turn
         * `isModified()` into an arbitrary resolver call.
         */
        private fun isValidIpv6Literal(host: String): Boolean {
            val literal = when {
                host.startsWith('[') && host.endsWith(']') -> host.substring(1, host.length - 1)
                host.startsWith('[') || host.endsWith(']') -> return false
                else -> host
            }

            val percentIndex = literal.indexOf('%')
            val address = if (percentIndex >= 0) {
                if (literal.indexOf('%', percentIndex + 1) >= 0) return false
                val zone = literal.substring(percentIndex + 1)
                if (!IPV6_ZONE_PATTERN.matches(zone)) return false
                literal.substring(0, percentIndex)
            } else {
                literal
            }
            if (address.isEmpty()) return false

            val compressionIndex = address.indexOf("::")
            if (compressionIndex >= 0 && address.indexOf("::", compressionIndex + 2) >= 0) return false

            val left = if (compressionIndex >= 0) address.substring(0, compressionIndex) else address
            val right = if (compressionIndex >= 0) address.substring(compressionIndex + 2) else ""
            val parts = buildList {
                if (left.isNotEmpty()) addAll(left.split(':'))
                if (right.isNotEmpty()) addAll(right.split(':'))
            }
            if (parts.any { it.isEmpty() }) return false

            var groupCount = 0
            for ((index, part) in parts.withIndex()) {
                if (part.contains('.')) {
                    if (index != parts.lastIndex || !isValidIpv4(part)) return false
                    groupCount += 2
                } else {
                    if (!IPV6_HEXTET_PATTERN.matches(part)) return false
                    groupCount++
                }
            }

            return if (compressionIndex >= 0) groupCount < 8 else groupCount == 8
        }
    }
}
