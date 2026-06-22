package priv.kit.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

open class PrivilegeUiViewModel : ViewModel() {
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

    val state: StateFlow<PrivilegeUiState> = store.state.asStateFlow()
    open val tcpModeEnabled: MutableStateFlow<Boolean> = store.tcpModeEnabled
    open val adbTcpPolicy: PrivilegeUiAdbTcpPolicy
        get() = store.config.adbTcpPolicy

    open fun attach(
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

    open fun updatePairingCode(value: String) = adbActions.updatePairingCode(value)

    open fun selectStartupMode(mode: PrivilegeUiStartupMode) {
        if (mode !in store.state.value.startupModes) return
        store.updateState { it.copy(selectedStartupMode = mode) }
        syncWirelessAdbStatusPolling()
        refreshTcpModeEnabledIfSelected()
    }

    open fun startRoot() = runtimeActions.startRoot()

    open fun copyManualCommand(context: Context) = manualShellActions.copyCommand(context)

    open fun pairWirelessAdb() = adbActions.pairWirelessAdb()

    open fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) = adbActions.startNotificationPairing(onNotificationPermissionRequired)

    open fun handleNotificationPermissionResult(granted: Boolean) =
        adbActions.handleNotificationPermissionResult(granted)

    open fun startWirelessAdb() = adbActions.startWirelessAdb()

    open fun startAdb() = adbActions.startAdb()

    open fun startWirelessAdbStatusPolling() = adbActions.startWirelessAdbStatusPolling()

    open fun refreshWirelessAdbStatus() = adbActions.refreshWirelessAdbStatus()

    open fun onHostResume() {
        syncWirelessAdbStatusPolling()
        if (store.state.value.selectedStartupMode == PrivilegeUiStartupMode.ADB) {
            refreshWirelessAdbStatus()
        }
        refreshTcpModeEnabledIfSelected()
    }

    open fun stopWirelessAdbStatusPolling() = adbActions.stopWirelessAdbStatusPolling()

    open fun enableTcpMode() = adbActions.enableTcpMode()

    open fun refreshTcpModeEnabled() = adbActions.refreshTcpModeEnabled()

    open fun startTcpAdb() = adbActions.startTcpAdb()

    open fun refreshDelegateStatus(providerId: String? = null) =
        delegateActions.refreshDelegateStatus(providerId)

    open fun authorizeOrStartDelegate(providerId: String) =
        delegateActions.authorizeOrStartDelegate(providerId)

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
