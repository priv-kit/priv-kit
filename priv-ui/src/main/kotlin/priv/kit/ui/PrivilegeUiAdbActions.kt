package priv.kit.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.PrivilegeRuntime
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiAdbActions(
    private val store: PrivilegeUiViewModelStore,
    private val runtimeActions: PrivilegeUiRuntimeActions,
) : AutoCloseable {
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
                val starter = PrivilegeRuntime.createAdbStarter(adbDeviceName = adbDeviceName)
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
            PrivilegeRuntime.startAdb(
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
                val starter = PrivilegeRuntime.createAdbStarter(
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
            val serverInfo = PrivilegeRuntime.startAdb(
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
                PrivilegeRuntime.createAdbStarter(
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
                    notificationPairingRunning = PrivilegeAdbPairingService.running.value,
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
                notificationPairingRunning = PrivilegeAdbPairingService.running.value,
            )
        }
    }

    fun registerPairingEventReceiver(context: Context) {
        store.pairingEventReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                handlePairingEvent(intent)
            }
        }
        store.pairingEventReceiver = receiver
        val filter = IntentFilter(PrivilegeAdbPairingService.actionPairingEvent(context))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    override fun close() {
        stopWirelessAdbStatusPolling()
        store.pairingEventReceiver?.let { receiver ->
            store.applicationContext?.let { context ->
                runCatching { context.unregisterReceiver(receiver) }
            }
        }
        store.pairingEventReceiver = null
    }

    private fun pollWirelessAdbStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            refreshWirelessAdbStatus(stop = stop, markChecking = false)
            if (!sleepWirelessAdbPolling(stop)) return
        }
    }

    private fun checkTcpModeEnabled(): Boolean {
        val starter = PrivilegeRuntime.createAdbStarter(
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
                        notificationPairingRunning = PrivilegeAdbPairingService.running.value,
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
                notificationPairingRunning = PrivilegeAdbPairingService.running.value,
            )
        }
    }

    private fun pollWirelessAdbStatusOnce(stop: AtomicBoolean) {
        val starter = PrivilegeRuntime.createAdbStarter(
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
                notificationPairingRunning = PrivilegeAdbPairingService.running.value,
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

    private fun handlePairingEvent(intent: Intent) {
        if (intent.action != PrivilegeAdbPairingService.actionPairingEvent(store.requireContext())) return
        val event = intent.getStringExtra(PrivilegeAdbPairingService.EXTRA_EVENT) ?: return
        val eventMessage = intent.getStringExtra(PrivilegeAdbPairingService.EXTRA_MESSAGE) ?: event
        val fingerprint = intent.getStringExtra(PrivilegeAdbPairingService.EXTRA_ADB_KEY_FINGERPRINT)
        val pairingStatus = when (event) {
            PrivilegeAdbPairingService.EVENT_SEARCHING -> PrivilegeUiAdbPairingStatus.SEARCHING
            PrivilegeAdbPairingService.EVENT_FOUND -> PrivilegeUiAdbPairingStatus.FOUND
            PrivilegeAdbPairingService.EVENT_PAIRING -> PrivilegeUiAdbPairingStatus.PAIRING
            PrivilegeAdbPairingService.EVENT_PAIRED -> PrivilegeUiAdbPairingStatus.PAIRED
            PrivilegeAdbPairingService.EVENT_FAILED -> PrivilegeUiAdbPairingStatus.FAILED
            PrivilegeAdbPairingService.EVENT_STOPPED -> PrivilegeUiAdbPairingStatus.NOT_PAIRED
            else -> store.state.value.pairingStatus
        }
        val running = event == PrivilegeAdbPairingService.EVENT_SEARCHING ||
            event == PrivilegeAdbPairingService.EVENT_FOUND ||
            event == PrivilegeAdbPairingService.EVENT_PAIRING
        store.updateState {
            it.copy(
                notificationPairingRunning = running,
                pairingStatus = pairingStatus,
                pairingMessage = eventMessage,
                adbKeyFingerprint = fingerprint ?: it.adbKeyFingerprint,
                wirelessPairingCheckStatus = if (event == PrivilegeAdbPairingService.EVENT_PAIRED) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    it.wirelessPairingCheckStatus
                },
                pairingCode = if (event == PrivilegeAdbPairingService.EVENT_PAIRED) "" else it.pairingCode,
                message = if (running || event == PrivilegeAdbPairingService.EVENT_FAILED) {
                    eventMessage
                } else {
                    store.idleMessage(it)
                },
            )
        }
        store.appendLog(eventMessage)
        if (event == PrivilegeAdbPairingService.EVENT_PAIRED) {
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
