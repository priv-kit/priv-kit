package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runInterruptible
import priv.kit.Privilege
import priv.kit.adb.isPrivilegeAdbLocalNetworkAccessFailure

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
    )

    fun observePairingNotificationEvents() {
        pairingActions.observePairingNotificationEvents()
    }

    fun updatePairingCode(value: String) {
        pairingActions.updatePairingCode(value)
    }

    fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        pairingActions.startNotificationPairing(
            onNotificationPermissionRequired = onNotificationPermissionRequired,
        )
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

    fun handleNotificationPermissionResult(permissionState: PrivilegeUiPermissionState) {
        pairingActions.handleNotificationPermissionResult(permissionState)
    }

    fun startWirelessAdb(
        onLocalNetworkPermissionRequired: (String) -> Unit = {},
    ) {
        refreshAdbStartPrerequisites()
        runtimeActions.runServerStartWorkflow(wirelessAdbStartWorkflow(onLocalNetworkPermissionRequired))
    }

    fun startAdb(
        onLocalNetworkPermissionRequired: (String) -> Unit = {},
    ) {
        val tcpModePort = store.currentTcpModePort()
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            tcpModePort != null
        ) {
            startStaticTcpAdb(onLocalNetworkPermissionRequired)
        } else {
            startWirelessAdb(onLocalNetworkPermissionRequired)
        }
    }

    fun startStaticTcpAdb(
        onLocalNetworkPermissionRequired: (String) -> Unit = {},
    ) {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        runtimeActions.runServerStartWorkflow(staticTcpAdbStartWorkflow(onLocalNetworkPermissionRequired))
    }

    private suspend fun prepareWirelessAdbCommand(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        session.checkActive()
        if (!ensureWifiConnectedForWirelessAdbStart(session)) return false
        session.appendStartupLog(store.text(R.string.priv_ui_checking_wireless_adb))
        if (!statusActions.forceWirelessAdbStatusRefreshForAction(session.stop)) return false
        session.checkActive()
        if (!ensureWifiConnectedForWirelessAdbStart(session)) return false
        if (!ensureWirelessDebuggingReadyForStart(session)) return false
        return ensureWirelessAdbPairedForStart(session)
    }

    fun enableTcpMode() {
        tcpActions.enableTcpMode()
    }

    fun directStartAttempts(): List<PrivilegeUiRuntimeStartAttempt> {
        return buildList {
            if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                add(staticTcpAdbStartWorkflow(onLocalNetworkPermissionRequired = {}))
            }
            if (isPrivilegeUiWirelessAdbSupported()) {
                add(wirelessAdbStartWorkflow(onLocalNetworkPermissionRequired = {}))
            }
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

    fun refreshAdbStartPrerequisites() {
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

    private fun ensureWifiConnectedForWirelessAdbStart(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        session.checkActive()
        val wifiConnected = store.isWifiConnected()
        if (wifiConnected) {
            store.updateState { it.copy(wifiConnected = true) }
            return true
        }
        val message = store.text(R.string.priv_ui_wifi_required_for_wireless_adb_start)
        adbConnectionSessions.closeWirelessPairingCheckSession()
        store.updateState {
            it.withWirelessAdbOffline(
                wifiConnected = false,
            )
        }
        if (session.showAttemptFeedback) {
            store.showSnackbar(message)
        }
        session.appendStartupLog(message)
        return false
    }

    private fun ensureWirelessAdbPairedForStart(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        session.checkActive()
        if (!shouldRequireWirelessPairingForStart(store.state.value.wirelessPairingCheckStatus)) {
            return true
        }
        val message = store.text(R.string.priv_ui_wireless_pair_required_for_wireless_adb_start)
        if (session.showAttemptFeedback) {
            store.showSnackbar(message)
        }
        session.appendStartupLog(message)
        return false
    }

    private fun ensureWirelessDebuggingReadyForStart(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        session.checkActive()
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
        if (session.showAttemptFeedback) {
            store.showSnackbar(message)
        }
        session.appendStartupLog(message)
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

    private fun wirelessAdbStartWorkflow(
        onLocalNetworkPermissionRequired: (String) -> Unit,
    ): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable, onLocalNetworkPermissionRequired)
            },
        ) {
            if (!prepareWirelessAdbCommand(this)) {
                PrivilegeUiRuntimeStartResult.Finished
            } else {
                val serverInfo = runInterruptible {
                    wirelessAdbStartAttempt(onLocalNetworkPermissionRequired).start(this)
                }
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
        }

    private fun staticTcpAdbStartWorkflow(
        onLocalNetworkPermissionRequired: (String) -> Unit,
    ): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable, onLocalNetworkPermissionRequired)
            },
        ) {
            if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
                return@Workflow PrivilegeUiRuntimeStartResult.Finished
            }
            val allowWirelessTcpSwitch = isPrivilegeUiWirelessAdbSupported()
            appendStartupLog(store.text(R.string.priv_ui_adb_static_check_action))
            if (!statusActions.forceTcpModeStatusRefreshForAction(stop)) {
                return@Workflow PrivilegeUiRuntimeStartResult.Finished
            }
            checkActive()
            val tcpPort = store.currentTcpModePort()
            if (tcpPort == null) {
                if (!allowWirelessTcpSwitch) {
                    refreshTcpModeEnabled()
                    showStaticTcpUnavailable(this, R.string.priv_ui_adb_static_port_unavailable)
                    return@Workflow PrivilegeUiRuntimeStartResult.Finished
                }
                return@Workflow startStaticTcpAdbThroughWireless(this)
            }
            appendStartupLog(store.text(R.string.priv_ui_adb_static_preparing))
            val preparation = runInterruptible {
                tcpActions.prepareStaticTcpForStart(tcpPort, this@Workflow)
            }
            checkActive()
            continueStaticTcpStart(
                session = this,
                tcpPort = preparation.tcpPort,
                authorizationStatus = preparation.authorizationStatus,
                allowWirelessTcpSwitch = allowWirelessTcpSwitch,
            )
        }

    private suspend fun continueStaticTcpStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int?,
        authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
        allowWirelessTcpSwitch: Boolean,
    ): PrivilegeUiRuntimeStartResult {
        if (tcpPort == null) {
            if (allowWirelessTcpSwitch) {
                return startStaticTcpAdbThroughWireless(session)
            }
            showStaticTcpUnavailable(session, R.string.priv_ui_adb_static_port_unavailable)
            return PrivilegeUiRuntimeStartResult.Finished
        }
        return when (authorizationStatus) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> {
                val serverInfo = runInterruptible {
                    tcpActions.tcpAdbStartAttempt(tcpPort).start(session)
                }
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
            PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
            PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            -> if (
                !shouldRequestStaticTcpAuthorizationForStart(
                    authorizationStatus = authorizationStatus,
                    showAttemptFeedback = session.showAttemptFeedback,
                ) || !tcpActions.requestTcpAuthorizationForStart(session, tcpPort)
            ) {
                PrivilegeUiRuntimeStartResult.Finished
            } else {
                val serverInfo = runInterruptible {
                    tcpActions.tcpAdbStartAttempt(tcpPort).start(session)
                }
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
            PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> {
                if (allowWirelessTcpSwitch) {
                    startStaticTcpAdbThroughWireless(session)
                } else {
                    showStaticTcpUnavailable(session, R.string.priv_ui_adb_static_service_stopped)
                    PrivilegeUiRuntimeStartResult.Finished
                }
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

    private fun wirelessAdbStartAttempt(
        onLocalNetworkPermissionRequired: (String) -> Unit,
    ): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable, onLocalNetworkPermissionRequired)
            },
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
                startupLogListener = startupLogListener,
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
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
        ) {
            val tcpPort = store.config.tcpPort
            val starter = Privilege.createAdbStarter(
                adbDeviceName = store.currentAdbDeviceNameOverride(),
            )
            appendStartupLog(store.text(R.string.priv_ui_tcp_enabling))
            starter.switchToTcp(
                tcpPort = tcpPort,
                options = privilegeUiStaticTcpSwitchOptions(
                    tcpPort = tcpPort,
                    managedWirelessAdbEnabled = store.managedWirelessAdbEnabledForStart(),
                    managedWirelessAdbStatus = store.state.value.managedWirelessAdbStatus,
                ),
            )
            Thread.sleep(PRIVILEGE_UI_TCP_START_DELAY_MILLIS)
            val tcpStartCheck = tcpActions.requireStaticTcpReady(starter, this)
            appendStartupLog(store.text(R.string.priv_ui_tcp_enabled))
            appendStartupLog(store.text(R.string.priv_ui_tcp_starting))
            tcpActions.startTcpAdbNow(tcpStartCheck.tcpPort, this)
        }
    }

    private fun handleWirelessAdbStartFailure(
        throwable: Throwable,
        onLocalNetworkPermissionRequired: (String) -> Unit,
    ): PrivilegeUiRuntimeStartFailureDisposition? {
        handleLocalNetworkAdbStartFailure(throwable, onLocalNetworkPermissionRequired)?.let {
            return it
        }
        if (!throwable.isAdbKeyNotAuthorizedFailure()) return null
        val message = store.text(R.string.priv_ui_wireless_pair_required_for_wireless_adb_start)
        val wirelessDebuggingStatus = currentWirelessDebuggingStatus()
        val notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
            store.notificationPairingOwnerId,
        )
        return PrivilegeUiRuntimeStartFailureDisposition(
            stateTransform = { current ->
                current.copy(
                    wirelessDebuggingStatus = wirelessDebuggingStatus,
                    wirelessPairingServiceStatus = if (wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON) {
                        current.wirelessPairingServiceStatus
                    } else {
                        PrivilegeUiWirelessAdbStatus.OFF
                    },
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    notificationPairingRunning = notificationPairingRunning,
                )
            },
            snackbarMessage = message,
            startupLogLines = listOf(
                message,
                throwable.toPrivilegeUiDiagnosticString(),
            ),
            afterCommit = {
                adbConnectionSessions.closeWirelessPairingCheckSession()
            },
        )
    }

    private fun handleLocalNetworkAdbStartFailure(
        throwable: Throwable,
        onLocalNetworkPermissionRequired: (String) -> Unit,
    ): PrivilegeUiRuntimeStartFailureDisposition? {
        if (!throwable.isPrivilegeAdbLocalNetworkAccessFailure()) return null
        val permission = privilegeUiRequiredLocalNetworkPermission(store.requireContext()) ?: return null
        val message = store.text(R.string.priv_ui_local_network_permission_missing)
        return PrivilegeUiRuntimeStartFailureDisposition(
            snackbarMessage = store.text(R.string.priv_ui_local_network_permission_required),
            startupLogLines = listOf(
                message,
                throwable.toPrivilegeUiDiagnosticString(),
            ),
            onUserActionRequired = {
                onLocalNetworkPermissionRequired(permission)
            },
        )
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
        if (activeTcpPort != null) {
            store.updateConfiguredTcpModePort(activeTcpPort)
        }
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

    private fun showStaticTcpUnavailable(
        session: PrivilegeUiRuntimeStartSession,
        messageRes: Int,
    ) {
        val message = store.text(messageRes)
        if (session.showAttemptFeedback) {
            store.showSnackbar(message)
        }
        session.appendStartupLog(message)
    }

}

private const val PRIVILEGE_UI_TCP_START_DELAY_MILLIS = 1_000L

internal fun shouldRequestStaticTcpAuthorizationForStart(
    authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    showAttemptFeedback: Boolean,
): Boolean =
    showAttemptFeedback && authorizationStatus in setOf(
        PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
        PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
        PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
        PrivilegeUiAdbTcpAuthorizationStatus.CHECKING,
        PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
    )
