package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runInterruptible
import priv.kit.Privilege

internal class PrivilegeUiAdbActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val coroutineScope: CoroutineScope,
) : AutoCloseable {
    private val adbConnectionSessions = PrivilegeUiAdbConnectionSessions()
    private val statusActions = PrivilegeUiAdbStatusActions(
        store = store,
        coroutineScope = coroutineScope,
        adbConnectionSessions = adbConnectionSessions,
    )
    private val tcpActions = PrivilegeUiAdbTcpActions(
        store = store,
        runtimeActions = runtimeActions,
        refreshTcpModeEnabled = { statusActions.refreshTcpModeEnabled() },
    )
    private val pairingActions = PrivilegeUiAdbPairingActions(
        store = store,
        coroutineScope = coroutineScope,
        enableTcpMode = { tcpActions.enableTcpMode() },
        refreshTcpModeEnabled = { statusActions.refreshTcpModeEnabled() },
    )

    fun observePairingEvents() {
        pairingActions.observePairingEvents()
    }

    fun updatePairingCode(value: String) {
        pairingActions.updatePairingCode(value)
    }

    fun pairWirelessAdb() {
        pairingActions.pairWirelessAdb()
    }

    fun cancelWirelessAdbPairing() {
        pairingActions.cancelWirelessAdbPairing()
    }

    fun toggleNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        pairingActions.toggleNotificationPairing(onNotificationPermissionRequired)
    }

    fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        pairingActions.startNotificationPairing(onNotificationPermissionRequired)
    }

    fun stopNotificationPairing() {
        pairingActions.stopNotificationPairing()
    }

    fun closePairingDialog() {
        pairingActions.closePairingDialog()
    }

    fun submitNotificationPairingCode() {
        pairingActions.submitNotificationPairingCode()
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        pairingActions.handleNotificationPermissionResult(granted)
    }

    fun startWirelessAdb() {
        runtimeActions.runServerStartWorkflow(wirelessAdbStartWorkflow())
    }

    fun startAdb() {
        val tcpModePort = store.currentTcpModePort()
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            tcpModePort != null
        ) {
            startStaticTcpAdb()
        } else {
            startWirelessAdb()
        }
    }

    fun startStaticTcpAdb() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        runtimeActions.runServerStartWorkflow(staticTcpAdbStartWorkflow())
    }

    private suspend fun prepareWirelessAdbCommand(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        if (!ensureWifiConnectedForWirelessAdbStart()) return false
        store.appendStartupLog(store.text(R.string.priv_ui_checking_wireless_adb))
        if (!statusActions.forceWirelessAdbStatusRefreshForAction(session.stop)) return false
        session.checkActive()
        if (!ensureWifiConnectedForWirelessAdbStart()) return false
        if (!ensureWirelessDebuggingReadyForStart()) return false
        return ensureWirelessAdbPairedForStart()
    }

    fun enableTcpMode() {
        tcpActions.enableTcpMode()
    }

    fun requestTcpAuthorization(tcpPort: Int? = store.currentTcpModePort()) {
        tcpActions.requestTcpAuthorization(tcpPort)
    }

    fun cancelTcpAuthorization() {
        tcpActions.cancelTcpAuthorization()
    }

    fun startTcpAdb(tcpPort: Int? = store.currentTcpModePort()) {
        tcpActions.startTcpAdb(tcpPort)
    }

    fun directStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect? {
        if (
            !store.state.value.canStartAdbDirectly(
                tcpModeEnabled = store.currentTcpModePort() != null,
                tcpPolicy = store.config.adbTcpPolicy,
                wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
                managedWirelessAdbEnabled = store.managedWirelessAdbEnabledForStart(),
            )
        ) {
            return null
        }
        val tcpPort = store.currentTcpModePort()
        return if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            tcpPort != null &&
            store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        ) {
            tcpActions.tcpAdbStartAttempt(tcpPort)
        } else {
            wirelessAdbStartAttempt()
        }
    }

    fun refreshTcpModeEnabled() {
        statusActions.refreshTcpModeEnabled()
    }

    fun startTcpModeStatusPolling(): AutoCloseable {
        return statusActions.startTcpModeStatusPolling()
    }

    fun stopTcpModeStatusPolling() {
        statusActions.stopTcpModeStatusPolling()
    }

    fun refreshAdbIdentityInfo() {
        statusActions.refreshAdbIdentityInfo()
    }

    fun startWirelessAdbStatusPolling(): AutoCloseable =
        statusActions.startWirelessAdbStatusPolling()

    fun refreshWirelessAdbStatus() {
        statusActions.refreshWirelessAdbStatus()
    }

    fun refreshWifiConnected() {
        statusActions.refreshWifiConnected()
    }

    fun stopWirelessAdbStatusPolling() {
        statusActions.stopWirelessAdbStatusPolling()
    }

    override fun close() {
        tcpActions.close()
        statusActions.stopTcpModeStatusPolling()
        pairingActions.close()
        statusActions.stopWirelessAdbStatusPolling()
    }

    private fun ensureWifiConnectedForWirelessAdbStart(): Boolean {
        val wifiConnected = store.isWifiConnected()
        if (wifiConnected) {
            store.updateState { it.copy(wifiConnected = true) }
            return true
        }
        val message = store.text(R.string.priv_ui_wifi_required_for_wireless_adb_start)
        adbConnectionSessions.closeWirelessPairingCheckSession()
        store.updateState {
            it.copy(
                wifiConnected = false,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
            )
        }
        store.showSnackbar(message)
        store.appendStartupLog(message)
        return false
    }

    private fun ensureWirelessAdbPairedForStart(): Boolean {
        if (!shouldRequireWirelessPairingForStart(store.state.value.wirelessPairingCheckStatus)) {
            return true
        }
        val message = store.text(R.string.priv_ui_wireless_pair_required_for_wireless_adb_start)
        store.showSnackbar(message)
        store.appendStartupLog(message)
        return false
    }

    private fun ensureWirelessDebuggingReadyForStart(): Boolean {
        val state = store.state.value
        val managedWirelessAdbStatus = managedWirelessAdbStatusForStart(state.managedWirelessAdbStatus)
        if (
            !shouldRequireWirelessDebuggingForStart(
                wirelessDebuggingStatus = state.wirelessDebuggingStatus,
                managedWirelessAdbStatus = managedWirelessAdbStatus,
            )
        ) {
            return true
        }
        val message = store.text(R.string.priv_ui_wireless_debugging_required_for_wireless_adb_start)
        store.showSnackbar(message)
        store.appendStartupLog(message)
        return false
    }

    private fun managedWirelessAdbStatusForStart(
        currentStatus: PrivilegeUiManagedWirelessAdbStatus,
    ): PrivilegeUiManagedWirelessAdbStatus {
        if (!store.config.enableManagedWirelessAdb || currentStatus == PrivilegeUiManagedWirelessAdbStatus.READY) {
            return currentStatus
        }
        val refreshedStatus = runCatching {
            Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            ).getWirelessDebuggingControlStatus().toUiManagedWirelessAdbStatus()
        }.getOrNull() ?: return currentStatus
        if (refreshedStatus != currentStatus) {
            store.updateState {
                it.copy(managedWirelessAdbStatus = refreshedStatus)
            }
        }
        return refreshedStatus
    }

    private fun wirelessAdbStartWorkflow(): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            onFailure = ::handleWirelessAdbStartFailure,
        ) {
            if (!prepareWirelessAdbCommand(this)) {
                PrivilegeUiRuntimeStartResult.Finished
            } else {
                val serverInfo = runInterruptible {
                    wirelessAdbStartAttempt().start(this)
                }
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
        }

    private fun staticTcpAdbStartWorkflow(): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
        ) {
            if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
                return@Workflow PrivilegeUiRuntimeStartResult.Finished
            }
            val allowWirelessTcpSwitch = isPrivilegeUiWirelessAdbSupported()
            store.appendStartupLog(store.text(R.string.priv_ui_adb_static_check_action))
            if (!statusActions.forceTcpModeStatusRefreshForAction(stop)) {
                return@Workflow PrivilegeUiRuntimeStartResult.Finished
            }
            checkActive()
            val tcpPort = store.currentTcpModePort()
            if (tcpPort == null) {
                if (!allowWirelessTcpSwitch) {
                    refreshTcpModeEnabled()
                    return@Workflow PrivilegeUiRuntimeStartResult.Finished
                }
                return@Workflow startStaticTcpAdbThroughWireless(this)
            }
            when (store.state.value.tcpAuthorizationStatus) {
                PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> {
                    val serverInfo = runInterruptible {
                        tcpActions.tcpAdbStartAttempt(tcpPort).start(this)
                    }
                    PrivilegeUiRuntimeStartResult.Connected(serverInfo)
                }
                PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
                PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
                PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
                -> {
                    if (!tcpActions.requestTcpAuthorizationForStart(this, tcpPort)) {
                        return@Workflow PrivilegeUiRuntimeStartResult.Finished
                    }
                    val serverInfo = runInterruptible {
                        tcpActions.tcpAdbStartAttempt(tcpPort).start(this)
                    }
                    PrivilegeUiRuntimeStartResult.Connected(serverInfo)
                }
                PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> {
                    if (allowWirelessTcpSwitch) {
                        startStaticTcpAdbThroughWireless(this)
                    } else {
                        PrivilegeUiRuntimeStartResult.Finished
                    }
                }
                PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING -> {
                    if (!tcpActions.requestTcpAuthorizationForStart(this, tcpPort)) {
                        return@Workflow PrivilegeUiRuntimeStartResult.Finished
                    }
                    val serverInfo = runInterruptible {
                        tcpActions.tcpAdbStartAttempt(tcpPort).start(this)
                    }
                    PrivilegeUiRuntimeStartResult.Connected(serverInfo)
                }
            }
        }

    private suspend fun startStaticTcpAdbThroughWireless(
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeUiRuntimeStartResult {
        if (!prepareWirelessAdbCommand(session)) {
            return PrivilegeUiRuntimeStartResult.Finished
        }
        val serverInfo = runInterruptible {
            staticTcpAdbThroughWirelessStartAttempt().start(session)
        }
        return PrivilegeUiRuntimeStartResult.Connected(serverInfo)
    }

    private fun wirelessAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            onFailure = ::handleWirelessAdbStartFailure,
        ) {
            val adbDeviceName = store.currentAdbDeviceNameOverride()
            val activeTcpPort = activeTcpPortForWirelessAdbStart(adbDeviceName)
            val options = privilegeUiWirelessAdbStartOptions(
                tcpPolicy = store.config.adbTcpPolicy,
                tcpPort = store.config.tcpPort,
                activeTcpPort = activeTcpPort,
                managedWirelessAdbEnabled = store.managedWirelessAdbEnabledForStart(),
                managedWirelessAdbStatus = store.state.value.managedWirelessAdbStatus,
            )
            val serverInfo = Privilege.startAdb(
                options = options,
                timeoutMillis = store.config.startTimeoutMillis,
                adbDeviceName = adbDeviceName,
                startupLogListener = store.startupLogListener,
            )
            updateTcpModeAfterWirelessAdbStart(
                activeTcpPort = activeTcpPort,
            )
            serverInfo
        }
    }

    private fun staticTcpAdbThroughWirelessStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            onFailure = ::handleWirelessAdbStartFailure,
        ) {
            val tcpPort = store.config.tcpPort
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            store.appendStartupLog(store.text(R.string.priv_ui_tcp_enabling))
            starter.switchToTcp(
                tcpPort = tcpPort,
                options = privilegeUiStaticTcpSwitchOptions(
                    tcpPort = tcpPort,
                    managedWirelessAdbEnabled = store.managedWirelessAdbEnabledForStart(),
                    managedWirelessAdbStatus = store.state.value.managedWirelessAdbStatus,
                ),
            )
            Thread.sleep(PRIVILEGE_UI_TCP_START_DELAY_MILLIS)
            val tcpStartCheck = tcpActions.requireStaticTcpReady(starter)
            store.appendStartupLog(store.text(R.string.priv_ui_tcp_enabled))
            store.appendStartupLog(store.text(R.string.priv_ui_tcp_starting))
            tcpActions.startTcpAdbNow(tcpStartCheck.tcpPort)
        }
    }

    private fun handleWirelessAdbStartFailure(throwable: Throwable): Boolean {
        if (!throwable.isAdbKeyNotAuthorizedFailure()) return false
        val message = store.text(R.string.priv_ui_wireless_pair_required_for_wireless_adb_start)
        val wirelessDebuggingStatus = currentWirelessDebuggingStatus()
        adbConnectionSessions.closeWirelessPairingCheckSession()
        store.updateState { current ->
            if (current.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
                current.copy(
                    busy = false,
                    runtimeProgressMessage = null,
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
                )
            } else {
                current.copy(
                    busy = false,
                    runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
                    serverInfo = null,
                    runtimeProgressMessage = null,
                    wirelessDebuggingStatus = wirelessDebuggingStatus,
                    wirelessPairingServiceStatus = if (wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON) {
                        current.wirelessPairingServiceStatus
                    } else {
                        PrivilegeUiWirelessAdbStatus.OFF
                    },
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
        }
        store.showSnackbar(message)
        store.appendStartupLog(message)
        store.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
        return true
    }

    private fun currentWirelessDebuggingStatus(): PrivilegeUiWirelessAdbStatus =
        privilegeUiWirelessDebuggingStatus(
            Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            ).getWirelessDebuggingControlStatus().wirelessDebuggingEnabled,
        )

    private fun activeTcpPortForWirelessAdbStart(adbDeviceName: String?): Int? {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return null
        return runCatching {
            Privilege.createAdbStarter(adbDeviceName = adbDeviceName).getActiveTcpPort()
        }.getOrNull()
    }

    private fun updateTcpModeAfterWirelessAdbStart(activeTcpPort: Int?) {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        store.updateTcpModePort(activeTcpPort)
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = if (activeTcpPort != null) {
                    PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
                } else {
                    PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN
                },
            )
        }
    }

}

private const val PRIVILEGE_UI_TCP_START_DELAY_MILLIS = 1_000L
