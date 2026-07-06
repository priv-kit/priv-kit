package priv.kit.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public open class PrivilegeUiViewModel @JvmOverloads public constructor(
    application: Application,
    public val config: PrivilegeUiConfig = PrivilegeUiConfig(),
) : AndroidViewModel(application) {
    private val store = PrivilegeUiViewModelStore(application).also(::addCloseable)
    private val runtimeActions = PrivilegeUiRuntimeActions(
        store = store,
        coroutineScope = viewModelScope,
    ).also(::addCloseable)
    private val manualShellActions = PrivilegeUiManualShellActions(store)
    private val adbActions = PrivilegeUiAdbActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
    ).also(::addCloseable)
    private val externalStartActions = PrivilegeUiExternalStartActions(
        store = store,
        runtimeActions = runtimeActions,
        coroutineScope = viewModelScope,
    ).also(::addCloseable)
    private var wirelessStatusPollingHandle: AutoCloseable? = null
    private var tcpModeStatusPollingHandle: AutoCloseable? = null
    private var externalStartStatusPollingHandle: AutoCloseable? = null
    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    public open val tcpModeEnabled: MutableStateFlow<Boolean> = store.tcpModeEnabled
    internal val snackbarMessages: SharedFlow<String> = store.snackbarMessages
    internal val selectedAdbStartupTab: StateFlow<PrivilegeUiAdbStartupTab?> =
        store.selectedAdbStartupTab.asStateFlow()
    public open val adbTcpPolicy: PrivilegeUiAdbTcpPolicy
        get() = store.config.adbTcpPolicy

    init {
        configure(config)
    }

    private fun configure(config: PrivilegeUiConfig) {
        store.config = config

        runtimeActions.configureOwnerDeathBehavior()
        adbActions.observePairingEvents()
        runtimeActions.installRuntimeWatchers()
        store.initializeState(config)

        runtimeActions.refreshRuntimeStatus()
        manualShellActions.loadCommand()
        externalStartActions.refreshExternalStartStatus()
        adbActions.refreshAdbIdentityInfo()
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun updatePairingCode(value: String) {
        adbActions.updatePairingCode(value)
    }

    internal fun selectAdbStartupTab(tab: PrivilegeUiAdbStartupTab) {
        store.selectedAdbStartupTab.value = tab
    }

    public open fun selectStartupMode(mode: PrivilegeUiStartupMode) {
        if (mode !in store.state.value.startupModes) return
        store.updateState { it.copy(selectedStartupMode = mode) }
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun startRoot() {
        runtimeActions.startRoot()
    }

    public open fun startAvailable() {
        if (
            store.state.value.busy ||
            store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED
        ) {
            return
        }
        adbActions.refreshWifiConnected()
        val attempts = store.state.value
            .directStartTargets(
                tcpModeEnabled = store.state.value.tcpModePort != null,
                tcpPolicy = store.config.adbTcpPolicy,
                wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
                managedWirelessAdbEnabled = store.config.enableManagedWirelessAdb &&
                    store.state.value.managedWirelessAdbStatus != PrivilegeUiManagedWirelessAdbStatus.UNDECLARED,
            )
            .mapNotNull { target ->
                when (target) {
                    PrivilegeUiDirectStartTarget.Adb -> adbActions.directStartAttempt()
                    is PrivilegeUiDirectStartTarget.External -> {
                        externalStartActions.directStartAttempt(target.providerId)
                    }
                    PrivilegeUiDirectStartTarget.Root -> runtimeActions.rootStartAttempt()
                }
            }
        if (attempts.isEmpty()) {
            runtimeActions.reportNoDirectStart()
        } else {
            runtimeActions.runServerStartFallback(attempts)
        }
    }

    public open fun stopServer() {
        runtimeActions.stopServer()
    }

    public open fun stopCurrentStart() {
        runtimeActions.stopCurrentStart()
    }

    public open fun copyManualCommand(context: Context) {
        manualShellActions.copyCommand(context)
    }

    public open fun copyStaticTcpCommand(context: Context) {
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_adb_static_command_clip_label),
            text = privilegeUiStaticTcpOpenCommand(store.config.tcpPort),
        )
    }

    public open fun copyStartupLog(context: Context) {
        val logText = store.state.value.startupLogLines.joinToString("\n")
        if (logText.isBlank()) return
        context.copyToClipboard(
            label = store.text(R.string.priv_ui_startup_log_clip_label),
            text = logText,
        )
    }

    public open fun pairWirelessAdb() {
        adbActions.pairWirelessAdb()
    }

    public open fun cancelWirelessAdbPairing() {
        adbActions.cancelWirelessAdbPairing()
    }

    public open fun toggleNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        adbActions.toggleNotificationPairing(onNotificationPermissionRequired)
    }

    public open fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        adbActions.startNotificationPairing(onNotificationPermissionRequired)
    }

    public open fun stopNotificationPairing() {
        adbActions.stopNotificationPairing()
    }

    public open fun closePairingDialog() {
        adbActions.closePairingDialog()
    }

    public open fun submitNotificationPairingCode() {
        adbActions.submitNotificationPairingCode()
    }

    public open fun handleNotificationPermissionResult(granted: Boolean) {
        adbActions.handleNotificationPermissionResult(granted)
    }

    public open fun startWirelessAdb() {
        adbActions.startWirelessAdb()
    }

    public open fun startAdb() {
        adbActions.startAdb()
    }

    public open fun startWirelessAdbStatusPolling(): AutoCloseable =
        if (isPrivilegeUiWirelessAdbSupported()) {
            adbActions.startWirelessAdbStatusPolling()
        } else {
            adbActions.stopWirelessAdbStatusPolling()
            PrivilegeUiNoopCloseable
        }

    public open fun refreshWirelessAdbStatus() {
        if (isPrivilegeUiWirelessAdbSupported()) {
            adbActions.refreshWirelessAdbStatus()
        }
    }

    public open fun onHostResume() {
        syncWirelessAdbStatusPolling()
        syncTcpModeStatusPolling()
        syncExternalStartStatusPolling()
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            isPrivilegeUiWirelessAdbSupported()
        ) {
            adbActions.refreshWirelessAdbStatus()
        }
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
            externalStartActions.refreshExternalStartStatus()
        }
        refreshTcpModeEnabledIfSelected()
    }

    public open fun stopWirelessAdbStatusPolling() {
        wirelessStatusPollingHandle?.close()
        wirelessStatusPollingHandle = null
        adbActions.stopWirelessAdbStatusPolling()
    }

    public open fun startTcpModeStatusPolling(): AutoCloseable =
        adbActions.startTcpModeStatusPolling()

    public open fun stopTcpModeStatusPolling() {
        tcpModeStatusPollingHandle?.close()
        tcpModeStatusPollingHandle = null
        adbActions.stopTcpModeStatusPolling()
    }

    public open fun enableTcpMode() {
        adbActions.enableTcpMode()
    }

    public open fun refreshTcpModeEnabled() {
        adbActions.refreshTcpModeEnabled()
    }

    public open fun startTcpAdb() {
        adbActions.startTcpAdb()
    }

    public open fun startStaticTcpAdb() {
        adbActions.startStaticTcpAdb()
    }

    public open fun refreshExternalStartStatus(providerId: String? = null) {
        externalStartActions.refreshExternalStartStatus(providerId)
    }

    public open fun startExternalStartStatusPolling(): AutoCloseable =
        externalStartActions.startExternalStartStatusPolling()

    public open fun stopExternalStartStatusPolling() {
        externalStartStatusPollingHandle?.close()
        externalStartStatusPollingHandle = null
        externalStartActions.stopExternalStartStatusPolling()
    }

    public open fun authorizeOrStartExternal(providerId: String) {
        externalStartActions.authorizeOrStartExternal(providerId)
    }

    private fun syncWirelessAdbStatusPolling() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            isPrivilegeUiWirelessAdbSupported()
        ) {
            if (wirelessStatusPollingHandle == null) {
                wirelessStatusPollingHandle = adbActions.startWirelessAdbStatusPolling()
            }
        } else {
            wirelessStatusPollingHandle?.close()
            wirelessStatusPollingHandle = null
        }
    }

    private fun syncTcpModeStatusPolling() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            if (tcpModeStatusPollingHandle == null) {
                tcpModeStatusPollingHandle = adbActions.startTcpModeStatusPolling()
            }
        } else {
            tcpModeStatusPollingHandle?.close()
            tcpModeStatusPollingHandle = null
        }
    }

    private fun syncExternalStartStatusPolling() {
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.EXTERNAL) {
            if (externalStartStatusPollingHandle == null) {
                externalStartStatusPollingHandle =
                    externalStartActions.startExternalStartStatusPolling()
            }
        } else {
            externalStartStatusPollingHandle?.close()
            externalStartStatusPollingHandle = null
        }
    }

    private fun refreshTcpModeEnabledIfSelected() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            adbActions.refreshTcpModeEnabled()
        }
    }
}
