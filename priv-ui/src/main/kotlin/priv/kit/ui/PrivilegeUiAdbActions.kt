package priv.kit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
        if (code.isBlank()) {
            store.updateState {
                it.copy(
                    message = store.text(R.string.priv_ui_pairing_code_required),
                    pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                    pairingMessage = store.text(R.string.priv_ui_pairing_default_message),
                )
            }
            return
        }

        val adbDeviceName = store.currentAdbDeviceNameOverride()
        store.updateState {
            it.copy(
                pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                pairingMessage = store.text(R.string.priv_ui_searching_pairing_port),
            )
        }
        runtimeActions.runBusy(
            message = store.text(R.string.priv_ui_discovering_pairing_port),
            onFailure = { throwable ->
                store.updateState {
                    it.copy(
                        pairingStatus = PrivilegeUiAdbPairingStatus.FAILED,
                        pairingMessage = throwable.failureMessage(),
                    )
                }
            },
            action = {
                val starter = Privilege.createAdbStarter(adbDeviceName = adbDeviceName)
                val pairingResult = starter.pair(pairingCode = code)
                maybeEnableTcpModeAfterPairing(starter)
                pairingResult
            },
            onSuccess = { pairingResult ->
                store.updateState { current ->
                    current.copy(
                        pairingCode = "",
                        pairingStatus = PrivilegeUiAdbPairingStatus.PAIRED,
                        pairingMessage = store.text(R.string.priv_ui_pairing_success_text),
                        wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                        wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                        adbKeyFingerprint = pairingResult.publicKeyFingerprint,
                    )
                }
                store.text(R.string.priv_ui_pairing_success_text)
            },
        )
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
                    message = store.text(R.string.priv_ui_notification_permission_required),
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
                message = message,
                notificationPairingRunning = true,
            )
        }
        store.appendLog(message)
        PrivilegeAdbPairingService.start(
            context = context,
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
    }

    fun stopNotificationPairing() {
        val context = store.requireContext()
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
                    message = store.text(R.string.priv_ui_notification_permission_required),
                )
            }
        }
    }

    fun startWirelessAdb() {
        runtimeActions.runServerStart(
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
    }

    fun startAdb() {
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
            store.tcpModeEnabled.value
        ) {
            startTcpAdb()
        } else {
            startWirelessAdb()
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

    fun startTcpAdb() {
        val tcpPort = store.config.tcpPort
        runtimeActions.runServerStart(
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

    fun refreshTcpModeEnabled() {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            store.tcpModeEnabled.value = false
            return
        }
        if (!store.tcpModeRefreshRunning.compareAndSet(false, true)) return
        Thread {
            try {
                store.tcpModeEnabled.value = checkTcpModeEnabled()
            } finally {
                store.tcpModeRefreshRunning.set(false)
            }
        }.apply {
            name = "priv-ui-tcp-mode-refresh"
            isDaemon = true
            start()
        }
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

    fun startWirelessAdbStatusPolling() {
        synchronized(store) {
            if (store.wirelessStatusPollingThread?.isAlive == true) return
            val stop = AtomicBoolean(false)
            val thread = Thread {
                pollWirelessAdbStatus(stop)
            }.apply {
                name = "priv-ui-wireless-adb-status"
                isDaemon = true
            }
            store.wirelessStatusPollingStop = stop
            store.wirelessStatusPollingThread = thread
            store.updateState {
                it.copy(
                    wirelessStatusPollingActive = true,
                    wirelessDebuggingStatus = it.wirelessDebuggingStatus.checkingIfUnknown(),
                    wirelessPairingServiceStatus = it.wirelessPairingServiceStatus.checkingIfUnknown(),
                    wirelessPairingCheckStatus = it.wirelessPairingCheckStatus.checkingIfUnknown(),
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
            thread.start()
        }
    }

    fun refreshWirelessAdbStatus() {
        val stop = synchronized(store) {
            store.wirelessStatusPollingStop ?: return
        }
        Thread {
            refreshWirelessAdbStatus(stop = stop, markChecking = true)
        }.apply {
            name = "priv-ui-wireless-adb-refresh"
            isDaemon = true
            start()
        }
    }

    fun stopWirelessAdbStatusPolling() {
        val thread: Thread?
        synchronized(store) {
            store.wirelessStatusPollingStop?.set(true)
            thread = store.wirelessStatusPollingThread
            store.wirelessStatusPollingStop = null
            store.wirelessStatusPollingThread = null
        }
        thread?.interrupt()
        store.updateState {
            it.copy(
                wirelessStatusPollingActive = false,
                notificationPairingRunning = PrivilegeAdbPairingService.running,
            )
        }
    }

    override fun close() {
        stopWirelessAdbStatusPolling()
        pairingEventsJob?.cancel()
        pairingEventsJob = null
        pairingEventScope.cancel()
    }

    private fun pollWirelessAdbStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            refreshWirelessAdbStatus(stop = stop, markChecking = false)
            if (!sleepWirelessAdbPolling(stop)) return
        }
    }

    private fun checkTcpModeEnabled(): Boolean {
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        return runCatching {
            starter.getActiveTcpPort() == store.config.tcpPort
        }.getOrDefault(false)
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
                wirelessPairingCheckStatus = it.wirelessPairingCheckStatus.checkingUnlessOn(),
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

    private fun PrivilegeUiWirelessAdbStatus.checkingUnlessOn(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.ON) this else PrivilegeUiWirelessAdbStatus.CHECKING

    private fun PrivilegeUiWirelessAdbStatus.checkingIfUnknown(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.UNKNOWN) PrivilegeUiWirelessAdbStatus.CHECKING else this

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
                message = if (event.running || event.type == PrivilegeAdbPairingEventType.FAILED) {
                    event.message
                } else {
                    store.idleMessage(it)
                },
            )
        }
        store.appendLog(event.message)
        if (event.type == PrivilegeAdbPairingEventType.PAIRED) {
            enableTcpModeAfterNotificationPairing()
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
