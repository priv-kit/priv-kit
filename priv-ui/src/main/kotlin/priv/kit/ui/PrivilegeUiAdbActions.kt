package priv.kit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import priv.kit.adb.PrivilegeAdbAuthorizationEndReason
import priv.kit.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.Privilege
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiAdbActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
) : AutoCloseable {
    private val pairingEventScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pairingEventsJob: Job? = null
    private val tcpModeStatusPolling = PrivilegeUiPollingSlot(
        threadName = "priv-ui-tcp-mode-status",
        onStart = {
            store.updateState { it.copy(tcpModeStatusPollingActive = true) }
        },
        onStop = {
            store.updateState { it.copy(tcpModeStatusPollingActive = false) }
        },
    ) { stop ->
        pollTcpModeStatus(stop)
    }
    private val wirelessAdbStatusPolling = PrivilegeUiPollingSlot(
        threadName = "priv-ui-wireless-adb-status",
        onStart = {
            store.updateState {
                it.copy(
                    wirelessStatusPollingActive = true,
                    wirelessDebuggingStatus = it.wirelessDebuggingStatus.checkingIfUnknown(),
                    wirelessPairingServiceStatus = it.wirelessPairingServiceStatus.checkingIfUnknown(),
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
        },
        onStop = {
            store.updateState {
                it.copy(
                    wirelessStatusPollingActive = false,
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
        },
    ) { stop ->
        pollWirelessAdbStatus(stop)
    }

    fun observePairingEvents() {
        pairingEventsJob?.cancel()
        pairingEventsJob = pairingEventScope.launch {
            PrivilegeAdbPairingService.pairingEvents.collect { event ->
                handlePairingEvent(event)
            }
        }
    }

    fun updatePairingCode(value: String) {
        store.updateState { current ->
            current.copy(
                pairingCode = value.toPrivilegeUiPairingCodeDigits(),
                pairingStatus = if (current.pairingStatus == PrivilegeUiAdbPairingStatus.FAILED) {
                    PrivilegeUiAdbPairingStatus.NOT_PAIRED
                } else {
                    current.pairingStatus
                },
                pairingMessage = if (current.pairingStatus == PrivilegeUiAdbPairingStatus.FAILED) {
                    store.text(R.string.priv_ui_pairing_default_message)
                } else {
                    current.pairingMessage
                },
            )
        }
    }

    fun pairWirelessAdb() {
        val code = store.state.value.pairingCode.trim()
        if (store.state.value.busy) return
        if (code.isBlank()) {
            store.updateState {
                it.copy(
                    serviceMessage = store.text(R.string.priv_ui_pairing_code_required),
                    pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                    pairingMessage = store.text(R.string.priv_ui_pairing_default_message),
                )
            }
            return
        }

        val adbDeviceName = store.currentAdbDeviceNameOverride()
        val generation = store.wirelessPairingGeneration.incrementAndGet()
        store.updateState {
            it.copy(
                busy = true,
                pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                pairingMessage = store.text(R.string.priv_ui_searching_pairing_port),
                wirelessPairingRunning = true,
                serviceMessage = store.text(R.string.priv_ui_discovering_pairing_port),
            )
        }
        store.appendLog(store.text(R.string.priv_ui_discovering_pairing_port))
        Thread {
            try {
                val starter = Privilege.createAdbStarter(adbDeviceName = adbDeviceName)
                val pairingPort = starter.discoverPairingPort(DIRECT_PAIRING_PORT_DISCOVERY_TIMEOUT_MILLIS)
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@Thread
                store.updateState {
                    it.copy(
                        pairingStatus = PrivilegeUiAdbPairingStatus.PAIRING,
                        pairingMessage = store.text(R.string.priv_ui_pairing_with_port),
                        serviceMessage = store.text(R.string.priv_ui_pairing_with_port),
                    )
                }
                val pairingResult = starter.pair(
                    port = pairingPort,
                    pairingCode = code,
                )
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@Thread
                maybeEnableTcpModeAfterPairing(starter)
                val resultMessage = store.text(R.string.priv_ui_pairing_success_text)
                store.updateState { current ->
                    current.copy(
                        busy = false,
                        pairingCode = "",
                        pairingStatus = PrivilegeUiAdbPairingStatus.PAIRED,
                        pairingMessage = resultMessage,
                        wirelessPairingRunning = false,
                        wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                        wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                        adbKeyFingerprint = pairingResult.publicKeyFingerprint,
                        serviceMessage = store.idleMessage(current),
                    )
                }
                store.appendLog(resultMessage)
            } catch (throwable: Throwable) {
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@Thread
                val failureMessage = throwable.failureMessage()
                store.updateState {
                    it.copy(
                        busy = false,
                        pairingStatus = PrivilegeUiAdbPairingStatus.FAILED,
                        pairingMessage = failureMessage,
                        wirelessPairingRunning = false,
                        serviceMessage = failureMessage,
                    )
                }
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            } finally {
                if (store.isCurrentWirelessPairingGeneration(generation)) {
                    store.wirelessPairingThread = null
                }
            }
        }.apply {
            name = "priv-ui-wireless-pairing"
            isDaemon = true
            store.wirelessPairingThread = this
            start()
        }
    }

    fun cancelWirelessAdbPairing() {
        val running = store.state.value.wirelessPairingRunning
        if (!running) return
        store.wirelessPairingGeneration.incrementAndGet()
        store.wirelessPairingThread?.interrupt()
        store.wirelessPairingThread = null
        val message = store.text(R.string.priv_ui_pairing_stopped)
        store.updateState { current ->
            current.copy(
                busy = false,
                pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                pairingMessage = store.text(R.string.priv_ui_pairing_default_message),
                wirelessPairingRunning = false,
                serviceMessage = message,
            )
        }
        store.appendLog(message)
    }

    fun toggleNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        if (store.state.value.notificationPairingRunning) {
            stopNotificationPairing()
        } else {
            startNotificationPairing(onNotificationPermissionRequired)
        }
    }

    fun startNotificationPairing(
        onNotificationPermissionRequired: () -> Unit = {},
    ) {
        val context = store.requireContext()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            store.startNotificationPairingAfterPermission = true
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                    pairingMessage = store.text(R.string.priv_ui_notification_permission_missing),
                    serviceMessage = store.text(R.string.priv_ui_notification_permission_required),
                )
            }
            onNotificationPermissionRequired()
            return
        }

        val message = store.text(R.string.priv_ui_notification_pairing_started)
        store.updateState {
            it.copy(
                pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                pairingMessage = message,
                serviceMessage = message,
                notificationPairingRunning = true,
            )
        }
        store.appendLog(message)
        store.notificationPairingStartedByOwner = true
        PrivilegeAdbPairingService.start(
            context = context,
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
    }

    fun stopNotificationPairing() {
        val context = store.requireContext()
        store.startNotificationPairingAfterPermission = false
        store.notificationPairingStartedByOwner = false
        PrivilegeAdbPairingService.stop(context)
    }

    fun handleNotificationPermissionResult(granted: Boolean) {
        if (granted && store.startNotificationPairingAfterPermission) {
            store.startNotificationPairingAfterPermission = false
            startNotificationPairing()
        } else {
            store.startNotificationPairingAfterPermission = false
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                    pairingMessage = store.text(R.string.priv_ui_notification_permission_missing),
                    serviceMessage = store.text(R.string.priv_ui_notification_permission_required),
                )
            }
        }
    }

    fun startWirelessAdb() {
        runtimeActions.runServerStart(wirelessAdbStartAttempt())
    }

    fun startAdb() {
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            store.tcpModeEnabled.value
        ) {
            startStaticTcpAdb()
        } else {
            startWirelessAdb()
        }
    }

    fun startStaticTcpAdb() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        if (!store.tcpModeEnabled.value) {
            refreshTcpModeEnabled()
            return
        }
        when (store.state.value.tcpAuthorizationStatus) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED -> startTcpAdb()
            PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
            PrivilegeUiAdbTcpAuthorizationStatus.FAILED,
            -> requestTcpAuthorization()
            PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN,
            PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            -> refreshTcpModeEnabled()
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING -> cancelTcpAuthorization()
            PrivilegeUiAdbTcpAuthorizationStatus.CHECKING -> Unit
        }
    }

    fun enableTcpMode() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        val tcpPort = store.config.tcpPort
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_tcp_enabling),
            onFailure = {
                store.tcpModeEnabled.value = false
            },
            action = {
                val starter = Privilege.createAdbStarter(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                )
                starter.switchToTcp(
                    currentPort = starter.discoverConnectPort(),
                    tcpPort = tcpPort,
                )
            },
            onSuccess = {
                refreshTcpModeEnabled()
                store.text(R.string.priv_ui_tcp_enabled)
            },
        )
    }

    fun requestTcpAuthorization() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) return
        store.tcpAuthorizationRequest?.let { request ->
            if (store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                return
            }
            store.tcpAuthorizationRequestGeneration.incrementAndGet()
            store.tcpAuthorizationRequest = null
            request.close()
        }
        val tcpPort = store.config.tcpPort
        val requestGeneration = store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
                serviceMessage = store.text(R.string.priv_ui_tcp_authorization_requesting),
            )
        }
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val request = starter.requestTcpAuthorization(
            tcpPort = tcpPort,
            timeoutMillis = store.config.adbAuthorizationTimeoutMillis,
        ) { result ->
            if (store.tcpAuthorizationRequestGeneration.get() != requestGeneration) {
                return@requestTcpAuthorization
            }
            store.tcpAuthorizationRequest = null
            if (result.authorized) {
                val message = store.text(R.string.priv_ui_tcp_authorization_allowed)
                store.updateState {
                    it.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
                        serviceMessage = message,
                    )
                }
                store.appendLog(message)
            } else {
                val status = when (result.endReason) {
                    PrivilegeAdbAuthorizationEndReason.FAILED -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
                    PrivilegeAdbAuthorizationEndReason.AUTOMATIC_TIMEOUT,
                    PrivilegeAdbAuthorizationEndReason.MANUAL_CANCELLED,
                    null,
                    -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
                }
                val message = result.failureMessage
                    ?: store.text(R.string.priv_ui_tcp_authorization_not_completed)
                store.updateState {
                    it.copy(
                        tcpAuthorizationStatus = status,
                        serviceMessage = message,
                    )
                }
                store.appendLog(message)
            }
        }
        if (
            store.tcpAuthorizationRequestGeneration.get() == requestGeneration &&
            store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING
        ) {
            store.tcpAuthorizationRequest = request
        } else {
            request.close()
        }
    }

    fun cancelTcpAuthorization() {
        finishTcpAuthorizationRequest()
    }

    fun finishPendingTcpAuthorizationOnHostResume() {
        if (store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
            finishTcpAuthorizationRequest()
        }
    }

    private fun finishTcpAuthorizationRequest() {
        val request = store.tcpAuthorizationRequest
        store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.tcpAuthorizationRequest = null
        if (request == null) {
            store.updateState { current ->
                if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                    current.copy(
                        tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                        serviceMessage = store.text(R.string.priv_ui_tcp_authorization_not_completed),
                    )
                } else {
                    current
                }
            }
            return
        }
        request.close()
        store.updateState { current ->
            if (current.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING) {
                current.copy(
                    tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED,
                    serviceMessage = store.text(R.string.priv_ui_tcp_authorization_not_completed),
                )
            } else {
                current
            }
        }
    }

    fun startTcpAdb() {
        runtimeActions.runServerStart(tcpAdbStartAttempt())
    }

    fun directStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect? {
        if (
            !store.state.value.canStartAdbDirectly(
                tcpModeEnabled = store.tcpModeEnabled.value,
                tcpPolicy = store.config.adbTcpPolicy,
                wirelessAdbSupported = isPrivilegeUiWirelessAdbSupported(),
            )
        ) {
            return null
        }
        return if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            store.tcpModeEnabled.value &&
            store.state.value.tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        ) {
            tcpAdbStartAttempt()
        } else {
            wirelessAdbStartAttempt()
        }
    }

    fun refreshTcpModeEnabled() {
        refreshTcpModeEnabled(markChecking = true)
    }

    private fun refreshTcpModeEnabled(markChecking: Boolean) {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            store.tcpModeEnabled.value = false
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return
        }
        if (!store.tcpModeRefreshRunning.compareAndSet(false, true)) return
        if (markChecking) {
            store.updateState {
                it.copy(tcpAuthorizationStatus = it.tcpAuthorizationStatus.checkingUnlessAuthorizedOrAuthorizing())
            }
        }
        Thread {
            try {
                refreshTcpModeAndAuthorization()
            } finally {
                store.tcpModeRefreshRunning.set(false)
            }
        }.apply {
            name = "priv-ui-tcp-mode-refresh"
            isDaemon = true
            start()
        }
    }

    fun startTcpModeStatusPolling(): AutoCloseable {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            stopTcpModeStatusPolling()
            return PrivilegeUiNoopCloseable
        }
        return tcpModeStatusPolling.acquire()
    }

    fun stopTcpModeStatusPolling() {
        tcpModeStatusPolling.stopAll()
    }

    fun refreshAdbIdentityInfo() {
        Thread {
            runCatching {
                Privilege.createAdbStarter(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                ).getIdentityInfo()
            }.onSuccess { info ->
                store.updateState {
                    it.copy(adbKeyFingerprint = info.publicKeyFingerprint)
                }
            }.onFailure { throwable ->
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            }
        }.apply {
            name = "priv-ui-adb-identity"
            isDaemon = true
            start()
        }
    }

    fun startWirelessAdbStatusPolling(): AutoCloseable =
        wirelessAdbStatusPolling.acquire()

    fun refreshWirelessAdbStatus() {
        val stop = wirelessAdbStatusPolling.currentStop() ?: return
        Thread {
            refreshWirelessAdbStatus(stop = stop, markChecking = true)
        }.apply {
            name = "priv-ui-wireless-adb-refresh"
            isDaemon = true
            start()
        }
    }

    fun stopWirelessAdbStatusPolling() {
        wirelessAdbStatusPolling.stopAll()
    }

    override fun close() {
        val request = store.tcpAuthorizationRequest
        store.tcpAuthorizationRequestGeneration.incrementAndGet()
        store.tcpAuthorizationRequest = null
        request?.close()
        stopTcpModeStatusPolling()
        stopOwnedNotificationPairing()
        stopWirelessAdbStatusPolling()
        pairingEventsJob?.cancel()
        pairingEventsJob = null
        pairingEventScope.cancel()
    }

    private fun stopOwnedNotificationPairing() {
        store.startNotificationPairingAfterPermission = false
        if (!store.notificationPairingStartedByOwner) return
        store.notificationPairingStartedByOwner = false
        store.applicationContext?.let(PrivilegeAdbPairingService::stop)
    }

    private fun pollWirelessAdbStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            refreshWirelessAdbStatus(stop = stop, markChecking = false)
            if (!sleepWirelessAdbPolling(stop)) return
        }
    }

    private fun pollTcpModeStatus(stop: AtomicBoolean) {
        var firstRefresh = true
        while (!stop.get()) {
            refreshTcpModeEnabled(markChecking = firstRefresh)
            firstRefresh = false
            if (!sleepTcpModeStatusPolling(stop)) return
        }
    }

    private fun refreshTcpModeAndAuthorization() {
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val tcpEnabled = runCatching {
            starter.getActiveTcpPort() == store.config.tcpPort
        }.getOrDefault(false)
        store.tcpModeEnabled.value = tcpEnabled
        if (!tcpEnabled) {
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return
        }

        val authorization = starter.checkTcpAuthorization(tcpPort = store.config.tcpPort)
        store.updateState {
            it.copy(tcpAuthorizationStatus = authorization.status.toUiTcpAuthorizationStatus())
        }
        authorization.failureMessage?.let(store::appendLog)
    }

    private fun maybeEnableTcpModeAfterPairing(starter: PrivilegeAdbStarter) {
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED ||
            store.tcpModeEnabled.value
        ) {
            return
        }
        runCatching {
            starter.switchToTcp(
                currentPort = starter.discoverConnectPort(),
                tcpPort = store.config.tcpPort,
            )
        }.onSuccess {
            store.tcpModeEnabled.value = true
            refreshTcpModeEnabled()
        }.onFailure { throwable ->
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }

    private fun refreshWirelessAdbStatus(
        stop: AtomicBoolean,
        markChecking: Boolean,
    ) {
        if (!store.wirelessStatusRefreshRunning.compareAndSet(false, true)) return
        if (markChecking) {
            markWirelessAdbStatusChecking()
        }
        try {
            runCatching {
                pollWirelessAdbStatusOnce(stop)
            }.onFailure { throwable ->
                if (stop.get()) return@onFailure
                store.updateState {
                    it.copy(
                        wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                        wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
                        wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                        notificationPairingRunning = PrivilegeAdbPairingService.running,
                    )
                }
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            }
        } finally {
            store.wirelessStatusRefreshRunning.set(false)
        }
    }

    private fun markWirelessAdbStatusChecking() {
        store.updateState {
            it.copy(
                wirelessDebuggingStatus = it.wirelessDebuggingStatus.checkingUnlessOn(),
                wirelessPairingServiceStatus = it.wirelessPairingServiceStatus.checkingUnlessOn(),
                notificationPairingRunning = PrivilegeAdbPairingService.running,
            )
        }
    }

    private fun pollWirelessAdbStatusOnce(stop: AtomicBoolean) {
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val timeoutMillis = store.config.wirelessStatusDiscoveryTimeoutMillis
        val connectPort = runCatching {
            starter.discoverConnectPort(timeoutMillis)
        }.getOrNull()
        if (stop.get()) return
        val pairingServiceOn = runCatching {
            starter.discoverPairingPort(timeoutMillis)
        }.isSuccess
        if (stop.get()) return
        val wirelessDebuggingOn = connectPort != null || pairingServiceOn

        store.updateState {
            it.copy(
                wirelessDebuggingStatus = if (wirelessDebuggingOn) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingServiceStatus = if (pairingServiceOn) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingCheckStatus = privilegeUiPassivePairingCheckStatus(
                    wirelessDebuggingOn = wirelessDebuggingOn,
                    currentStatus = it.wirelessPairingCheckStatus,
                ),
                notificationPairingRunning = PrivilegeAdbPairingService.running,
            )
        }
    }

    private fun sleepWirelessAdbPolling(stop: AtomicBoolean): Boolean =
        try {
            Thread.sleep(store.config.wirelessStatusPollIntervalMillis)
            !stop.get()
        } catch (_: InterruptedException) {
            false
        }

    private fun sleepTcpModeStatusPolling(stop: AtomicBoolean): Boolean =
        try {
            Thread.sleep(store.config.wirelessStatusPollIntervalMillis)
            !stop.get()
        } catch (_: InterruptedException) {
            false
        }

    private fun PrivilegeUiWirelessAdbStatus.checkingUnlessOn(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.ON) this else PrivilegeUiWirelessAdbStatus.CHECKING

    private fun PrivilegeUiWirelessAdbStatus.checkingIfUnknown(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.UNKNOWN) PrivilegeUiWirelessAdbStatus.CHECKING else this

    private fun PrivilegeUiAdbTcpAuthorizationStatus.checkingUnlessAuthorizedOrAuthorizing():
        PrivilegeUiAdbTcpAuthorizationStatus =
        when (this) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            -> this
            else -> PrivilegeUiAdbTcpAuthorizationStatus.CHECKING
        }

    private fun PrivilegeAdbAuthorizationStatus.toUiTcpAuthorizationStatus(): PrivilegeUiAdbTcpAuthorizationStatus =
        when (this) {
            PrivilegeAdbAuthorizationStatus.AUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
            PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
            PrivilegeAdbAuthorizationStatus.UNAVAILABLE -> PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE
            PrivilegeAdbAuthorizationStatus.ERROR -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
        }

    private fun handlePairingEvent(event: PrivilegeAdbPairingEvent) {
        val pairingStatus = when (event.type) {
            PrivilegeAdbPairingEventType.SEARCHING -> PrivilegeUiAdbPairingStatus.SEARCHING
            PrivilegeAdbPairingEventType.FOUND -> PrivilegeUiAdbPairingStatus.FOUND
            PrivilegeAdbPairingEventType.PAIRING -> PrivilegeUiAdbPairingStatus.PAIRING
            PrivilegeAdbPairingEventType.PAIRED -> PrivilegeUiAdbPairingStatus.PAIRED
            PrivilegeAdbPairingEventType.FAILED -> PrivilegeUiAdbPairingStatus.FAILED
            PrivilegeAdbPairingEventType.STOPPED -> PrivilegeUiAdbPairingStatus.NOT_PAIRED
        }
        store.updateState {
            it.copy(
                notificationPairingRunning = event.running,
                pairingStatus = pairingStatus,
                pairingMessage = event.message,
                adbKeyFingerprint = event.fingerprint ?: it.adbKeyFingerprint,
                wirelessPairingCheckStatus = if (event.type == PrivilegeAdbPairingEventType.PAIRED) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    it.wirelessPairingCheckStatus
                },
                pairingCode = if (event.type == PrivilegeAdbPairingEventType.PAIRED) "" else it.pairingCode,
                serviceMessage = if (event.running || event.type == PrivilegeAdbPairingEventType.FAILED) {
                    event.message
                } else {
                    store.idleMessage(it)
                },
            )
        }
        store.appendLog(event.message)
        if (event.type == PrivilegeAdbPairingEventType.PAIRED) {
            store.notificationPairingStartedByOwner = false
            enableTcpModeAfterNotificationPairing()
        } else if (event.type == PrivilegeAdbPairingEventType.STOPPED) {
            store.notificationPairingStartedByOwner = false
        }
    }

    private fun enableTcpModeAfterNotificationPairing() {
        if (
            store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED &&
            !store.tcpModeEnabled.value
        ) {
            enableTcpMode()
        }
    }

    private fun wirelessAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect =
        PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_wireless_adb_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
        ) {
            Privilege.startAdb(
                options = PrivilegeAdbStartOptions(),
                timeoutMillis = store.config.startTimeoutMillis,
                adbDeviceName = store.currentAdbDeviceNameOverride(),
                startupLogListener = store.startupLogListener,
            )
        }

    private fun tcpAdbStartAttempt(): PrivilegeUiRuntimeStartAttempt.Connect {
        val tcpPort = store.config.tcpPort
        return PrivilegeUiRuntimeStartAttempt.Connect(
            message = store.text(R.string.priv_ui_tcp_starting),
            startupSource = store.text(R.string.priv_ui_auth_method_adb),
        ) {
            val serverInfo = Privilege.startAdb(
                options = PrivilegeAdbStartOptions(
                    tcpMode = true,
                    tcpPort = tcpPort,
                    discoverPort = false,
                ),
                timeoutMillis = store.config.startTimeoutMillis,
                adbDeviceName = store.currentAdbDeviceNameOverride(),
                startupLogListener = store.startupLogListener,
            )
            store.tcpModeEnabled.value = true
            serverInfo
        }
    }
}

internal fun privilegeUiPassivePairingCheckStatus(
    wirelessDebuggingOn: Boolean,
    currentStatus: PrivilegeUiWirelessAdbStatus,
): PrivilegeUiWirelessAdbStatus =
    when {
        !wirelessDebuggingOn -> PrivilegeUiWirelessAdbStatus.UNKNOWN
        currentStatus == PrivilegeUiWirelessAdbStatus.ON -> PrivilegeUiWirelessAdbStatus.ON
        else -> PrivilegeUiWirelessAdbStatus.UNKNOWN
    }

private fun PrivilegeUiViewModelStore.isCurrentWirelessPairingGeneration(generation: Long): Boolean =
    wirelessPairingGeneration.get() == generation

private const val DIRECT_PAIRING_PORT_DISCOVERY_TIMEOUT_MILLIS = 5_000L
