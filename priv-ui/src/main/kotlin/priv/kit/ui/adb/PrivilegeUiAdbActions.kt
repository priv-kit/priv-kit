package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import kotlinx.coroutines.CoroutineScope
import priv.kit.core.Privilege
import priv.kit.core.adb.isPrivilegeAdbLocalNetworkAccessFailure
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator

internal class PrivilegeUiAdbActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
    private val coroutineScope: CoroutineScope,
    private val acquireInteractivePermit: () -> AutoCloseable? =
        PrivilegeUiStartGate.newInteractivePermitAcquirer(),
    private val hasInteractionHost: () -> Boolean = { true },
) : AutoCloseable {
    private val staticTcpConfirmationController = PrivilegeUiStaticTcpConfirmationController()
    private val statusActions = PrivilegeUiAdbStatusActions(
        store = store,
        coroutineScope = coroutineScope,
    )
    private val tcpActions = PrivilegeUiAdbTcpActions(
        store = store,
        runtimeActions = runtimeActions,
        refreshTcpModeEnabled = { statusActions.refreshTcpModeEnabled() },
    )
    private val pairingActions = PrivilegeUiAdbPairingActions(
        store = store,
        coroutineScope = coroutineScope,
        enableTcpMode = {
            enableTcpMode()
        },
        acquireInteractivePermit = acquireInteractivePermit,
        hasInteractionHost = hasInteractionHost,
    )

    val staticTcpSwitchConfirmation = staticTcpConfirmationController.pendingAction

    fun observePairingNotificationEvents() {
        pairingActions.observePairingNotificationEvents()
    }

    fun updatePairingCode(value: String) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.updatePairingCode(value)
    }

    suspend fun startNotificationPairing(
        requestNotificationPermission: suspend () -> PrivilegeUiPermissionState? = { null },
    ) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.startNotificationPairing(requestNotificationPermission)
    }

    fun stopNotificationPairing() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.stopNotificationPairing()
    }

    fun cancelPendingPairingStart() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.cancelPendingPairingStart()
    }

    fun cancelPairingWithoutInteractionHost() {
        pairingActions.cancelPairingWithoutInteractionHost()
    }

    fun continuePairingWithoutNotification() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.continuePairingWithoutNotification()
    }

    fun continuePendingPairingIfNotificationPermissionGranted() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.continuePendingPairingIfNotificationPermissionGranted()
    }

    fun closePairingDialog() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.closePairingDialog()
    }

    fun submitNotificationPairingCode() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        pairingActions.submitNotificationPairingCode()
    }

    fun startWirelessAdb(
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState? = { null },
    ) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        refreshAdbStartPrerequisites()
        runtimeActions.runServerStartWorkflow(wirelessAdbStartWorkflow(requestLocalNetworkPermission))
    }

    fun startAdb(
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState? = { null },
    ) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        val tcpModePort = store.currentTcpModePort()
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            tcpModePort != null
        ) {
            startStaticTcpAdb(requestLocalNetworkPermission)
        } else {
            startWirelessAdb(requestLocalNetworkPermission)
        }
    }

    fun startStaticTcpAdb(
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState? = { null },
    ) {
        startStaticTcpAdb(
            requestLocalNetworkPermission = requestLocalNetworkPermission,
            confirmTcpSwitch = true,
        )
    }

    private fun startStaticTcpAdb(
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState?,
        confirmTcpSwitch: Boolean,
    ) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        runtimeActions.runServerStartWorkflow(
            staticTcpAdbStartWorkflow(
                requestLocalNetworkPermission = requestLocalNetworkPermission,
                confirmTcpSwitch = confirmTcpSwitch,
            ),
        )
    }

    private suspend fun prepareWirelessAdbCommand(
        session: PrivilegeUiRuntimeStartSession,
    ): Boolean {
        session.checkActive()
        if (!ensureWifiConnectedForWirelessAdbStart(session)) return false
        session.appendStartupLog(store.text(R.string.priv_ui_checking_wireless_adb))
        if (!statusActions.forceWirelessAdbStatusRefreshForAction()) return false
        session.checkActive()
        if (!ensureWifiConnectedForWirelessAdbStart(session)) return false
        if (!ensureWirelessDebuggingReadyForStart(session)) return false
        return ensureWirelessAdbPairedForStart(session)
    }

    suspend fun enableTcpMode() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val interactionPermit = acquireInteractivePermit() ?: return
        try {
            if (
                staticTcpConfirmationController.awaitConfirmation(
                    PrivilegeUiStaticTcpSwitchAction.ENABLE_PORT,
                ) &&
                store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
                store.state.value.runtimeStatus != PrivilegeUiRuntimeStatus.CONNECTED
            ) {
                tcpActions.enableTcpMode()
            }
        } finally {
            interactionPermit.close()
        }
    }

    fun confirmStaticTcpSwitch() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            staticTcpConfirmationController.cancel()
            return
        }
        if (store.state.value.runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
            staticTcpConfirmationController.cancel()
            return
        }
        staticTcpConfirmationController.confirm()
    }

    fun cancelStaticTcpSwitch() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        staticTcpConfirmationController.cancel()
    }

    fun directStartAttempts(): List<PrivilegeUiRuntimeStartAttempt> {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return emptyList()
        return buildList {
            if (store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
                add(
                    staticTcpAdbStartWorkflow(
                        requestLocalNetworkPermission = { null },
                        confirmTcpSwitch = false,
                    ),
                )
            }
            if (isPrivilegeUiWirelessAdbSupported()) {
                add(wirelessAdbStartWorkflow(requestLocalNetworkPermission = { null }))
            }
        }
    }

    fun refreshTcpModeEnabled() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        statusActions.refreshTcpModeEnabled()
    }

    suspend fun refreshTcpModeEnabledNow() = statusActions.forceTcpModeStatusRefreshForAction()

    suspend fun pollTcpModeStatus() = statusActions.pollTcpModeStatus()

    suspend fun refreshAdbIdentityInfoNow() = statusActions.refreshAdbIdentityInfoNow()

    suspend fun refreshWirelessAdbStatusNow() =
        statusActions.forceWirelessAdbStatusRefreshForAction()

    suspend fun pollWirelessAdbStatus() = statusActions.pollWirelessAdbStatus()

    fun refreshAdbStartPrerequisites() {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        statusActions.refreshWifiConnected()
    }

    override fun close() {
        staticTcpConfirmationController.cancel()
        pairingActions.close()
        statusActions.close()
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
        statusActions.resetWirelessPairingSession()
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
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState?,
    ): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable)
            },
        ) {
            runWithLocalNetworkPermissionRetry(this, requestLocalNetworkPermission) {
                if (!prepareWirelessAdbCommand(this)) {
                    PrivilegeUiRuntimeStartResult.Finished
                } else {
                    val serverInfo = wirelessAdbStartAttempt().start(this)
                    PrivilegeUiRuntimeStartResult.Connected(serverInfo)
                }
            }
        }

    private fun staticTcpAdbStartWorkflow(
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState?,
        confirmTcpSwitch: Boolean,
    ): PrivilegeUiRuntimeStartAttempt.Workflow =
        PrivilegeUiRuntimeStartAttempt.Workflow(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable)
            },
        ) {
            runWithLocalNetworkPermissionRetry(this, requestLocalNetworkPermission) {
                if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
                    return@runWithLocalNetworkPermissionRetry PrivilegeUiRuntimeStartResult.Finished
                }
                val allowWirelessTcpSwitch = isPrivilegeUiWirelessAdbSupported()
                appendStartupLog(store.text(R.string.priv_ui_adb_static_check_action))
                if (!statusActions.forceTcpModeStatusRefreshForAction()) {
                    return@runWithLocalNetworkPermissionRetry PrivilegeUiRuntimeStartResult.Finished
                }
                checkActive()
                val tcpPort = store.currentTcpModePort()
                if (tcpPort == null) {
                    if (!allowWirelessTcpSwitch) {
                        refreshTcpModeEnabled()
                        showStaticTcpUnavailable(this, R.string.priv_ui_adb_static_port_unavailable)
                        return@runWithLocalNetworkPermissionRetry PrivilegeUiRuntimeStartResult.Finished
                    }
                    return@runWithLocalNetworkPermissionRetry handleStaticTcpSwitchRequired(
                        session = this,
                        confirmTcpSwitch = confirmTcpSwitch,
                    )
                }
                appendStartupLog(store.text(R.string.priv_ui_adb_static_preparing))
                val preparation = tcpActions.prepareStaticTcpForStart(tcpPort, this@Workflow)
                checkActive()
                continueStaticTcpStart(
                    session = this,
                    tcpPort = preparation.tcpPort,
                    authorizationStatus = preparation.authorizationStatus,
                    allowWirelessTcpSwitch = allowWirelessTcpSwitch,
                    confirmTcpSwitch = confirmTcpSwitch,
                )
            }
        }

    private suspend fun continueStaticTcpStart(
        session: PrivilegeUiRuntimeStartSession,
        tcpPort: Int?,
        authorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
        allowWirelessTcpSwitch: Boolean,
        confirmTcpSwitch: Boolean,
    ): PrivilegeUiRuntimeStartResult {
        if (tcpPort == null) {
            if (allowWirelessTcpSwitch) {
                return handleStaticTcpSwitchRequired(session, confirmTcpSwitch)
            }
            showStaticTcpUnavailable(session, R.string.priv_ui_adb_static_port_unavailable)
            return PrivilegeUiRuntimeStartResult.Finished
        }
        return when (authorizationStatus) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> {
                val serverInfo = tcpActions.tcpAdbStartAttempt(tcpPort).start(session)
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
                val serverInfo = tcpActions.tcpAdbStartAttempt(tcpPort).start(session)
                PrivilegeUiRuntimeStartResult.Connected(serverInfo)
            }
            PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE -> {
                if (allowWirelessTcpSwitch) {
                    handleStaticTcpSwitchRequired(session, confirmTcpSwitch)
                } else {
                    showStaticTcpUnavailable(session, R.string.priv_ui_adb_static_service_stopped)
                    PrivilegeUiRuntimeStartResult.Finished
                }
            }
        }
    }

    private suspend fun handleStaticTcpSwitchRequired(
        session: PrivilegeUiRuntimeStartSession,
        confirmTcpSwitch: Boolean,
    ): PrivilegeUiRuntimeStartResult {
        if (!confirmTcpSwitch) return PrivilegeUiRuntimeStartResult.Finished
        if (
            !staticTcpConfirmationController.awaitConfirmation(
                PrivilegeUiStaticTcpSwitchAction.START_SERVICE,
            )
        ) {
            return PrivilegeUiRuntimeStartResult.Finished
        }
        session.checkActive()
        return startStaticTcpAdbThroughWireless(session)
    }

    private suspend fun startStaticTcpAdbThroughWireless(
        session: PrivilegeUiRuntimeStartSession,
    ): PrivilegeUiRuntimeStartResult {
        if (!prepareWirelessAdbCommand(session)) {
            return PrivilegeUiRuntimeStartResult.Finished
        }
        val serverInfo = staticTcpAdbThroughWirelessStartAttempt().start(session)
        return PrivilegeUiRuntimeStartResult.Connected(serverInfo)
    }

    private fun wirelessAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
            runtimeStartSource = PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            onFailure = { throwable ->
                handleWirelessAdbStartFailure(throwable)
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
            val serverInfo = PrivilegeRuntimeStartCoordinator.startAdb(
                launch = requireRuntimeClientLaunch(),
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
            kotlinx.coroutines.delay(PRIVILEGE_UI_TCP_START_DELAY_MILLIS)
            val activeTcpPort = tcpActions.requireStaticTcpReady(starter, this)
            appendStartupLog(store.text(R.string.priv_ui_tcp_enabled))
            appendStartupLog(store.text(R.string.priv_ui_tcp_starting))
            tcpActions.startTcpAdbNow(activeTcpPort, this)
        }
    }

    private fun handleWirelessAdbStartFailure(
        throwable: Throwable,
    ): PrivilegeUiRuntimeStartFailureDisposition? {
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
                statusActions.resetWirelessPairingSession()
            },
        )
    }

    private suspend fun runWithLocalNetworkPermissionRetry(
        session: PrivilegeUiRuntimeStartSession,
        requestLocalNetworkPermission: suspend (String) -> PrivilegeUiPermissionState?,
        action: suspend () -> PrivilegeUiRuntimeStartResult,
    ): PrivilegeUiRuntimeStartResult {
        try {
            return action()
        } catch (throwable: Throwable) {
            val permission = privilegeUiRequiredLocalNetworkPermission(store.requireContext())
                ?.takeIf { throwable.isPrivilegeAdbLocalNetworkAccessFailure() }
                ?: throw throwable
            session.appendStartupLog(store.text(R.string.priv_ui_local_network_permission_missing))
            session.appendStartupLog(throwable.toPrivilegeUiDiagnosticString())
            if (!session.showAttemptFeedback) return PrivilegeUiRuntimeStartResult.Finished
            store.showSnackbar(store.text(R.string.priv_ui_local_network_permission_required))
            if (requestLocalNetworkPermission(permission) != PrivilegeUiPermissionState.Granted) {
                return PrivilegeUiRuntimeStartResult.Finished
            }
            session.checkActive()
            return action()
        }
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
