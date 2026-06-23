package priv.kit.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public open class PrivilegeUiViewModel public constructor() : ViewModel() {
    private val store = PrivilegeUiViewModelStore().also { store ->
        addCloseable { store.close() }
    }
    private val runtimeActions = PrivilegeUiRuntimeActions(store).also { actions ->
        addCloseable { actions.clear() }
    }
    private val manualShellActions = PrivilegeUiManualShellActions(store)
    private val adbActions = PrivilegeUiAdbActions(store, runtimeActions).also { actions ->
        addCloseable { actions.clear() }
    }
    private val delegateActions = PrivilegeUiDelegateActions(store, runtimeActions)

    public val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    public open val tcpModeEnabled: MutableStateFlow<Boolean> = store.tcpModeEnabled
    public open val adbTcpPolicy: PrivilegeUiAdbTcpPolicy
        get() = store.config.adbTcpPolicy

    public open fun attach(
        context: Context,
        config: PrivilegeUiConfig = PrivilegeUiConfig(),
    ) {
        val appContext = context.applicationContext
        if (store.attached && store.applicationContext === appContext && store.config == config) return
        store.attached = true
        store.applicationContext = appContext
        store.config = config

        runtimeActions.configureOwnerDeathBehavior()
        adbActions.registerPairingEventReceiver(appContext)
        runtimeActions.installRuntimeWatchers()
        store.initializeState(config)

        runtimeActions.refreshRuntimeStatus()
        manualShellActions.loadCommand()
        delegateActions.refreshDelegateStatus()
        syncWirelessAdbStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun updatePairingCode(value: String) {
        adbActions.updatePairingCode(value)
    }

    public open fun selectStartupMode(mode: PrivilegeUiStartupMode) {
        if (mode !in store.state.value.startupModes) return
        store.updateState { it.copy(selectedStartupMode = mode) }
        syncWirelessAdbStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    public open fun startRoot() {
        runtimeActions.startRoot()
    }

    public open fun copyManualCommand(context: Context) {
        manualShellActions.copyCommand(context)
    }

    public open fun pairWirelessAdb() {
        adbActions.pairWirelessAdb()
    }

    public open fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        adbActions.startNotificationPairing(onNotificationPermissionRequired)
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

    public open fun startWirelessAdbStatusPolling() {
        adbActions.startWirelessAdbStatusPolling()
    }

    public open fun refreshWirelessAdbStatus() {
        adbActions.refreshWirelessAdbStatus()
    }

    public open fun onHostResume() {
        syncWirelessAdbStatusPolling()
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB) {
            refreshWirelessAdbStatus()
        }
        refreshTcpModeEnabledIfSelected()
    }

    public open fun stopWirelessAdbStatusPolling() {
        adbActions.stopWirelessAdbStatusPolling()
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

    public open fun refreshDelegateStatus(providerId: String? = null) {
        delegateActions.refreshDelegateStatus(providerId)
    }

    public open fun authorizeOrStartDelegate(providerId: String) {
        delegateActions.authorizeOrStartDelegate(providerId)
    }

    private fun syncWirelessAdbStatusPolling() {
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB) {
            startWirelessAdbStatusPolling()
        } else {
            stopWirelessAdbStatusPolling()
        }
    }

    private fun refreshTcpModeEnabledIfSelected() {
        if (
            store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB &&
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED
        ) {
            refreshTcpModeEnabled()
        }
    }
}
