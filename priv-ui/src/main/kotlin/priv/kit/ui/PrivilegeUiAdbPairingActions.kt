package priv.kit.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import priv.kit.Privilege
import priv.kit.adb.PrivilegeAdbStarter

internal class PrivilegeUiAdbPairingActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val enableTcpMode: () -> Unit,
    private val refreshTcpModeEnabled: () -> Unit,
) : AutoCloseable {
    private var pairingEventsJob: Job? = null

    fun observePairingEvents() {
        pairingEventsJob?.cancel()
        pairingEventsJob = coroutineScope.launch(Dispatchers.Main.immediate + CoroutineName("priv-ui-pairing-events")) {
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
        if (!code.isPrivilegeUiPairingCode()) return

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
        val job = coroutineScope.launch(
            context = Dispatchers.IO + CoroutineName("priv-ui-wireless-pairing"),
            start = CoroutineStart.LAZY,
        ) pairing@{
            try {
                val starter = Privilege.createAdbStarter(adbDeviceName = adbDeviceName)
                val pairingPort = starter.discoverPairingPort(DIRECT_PAIRING_PORT_DISCOVERY_TIMEOUT_MILLIS)
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@pairing
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
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@pairing
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
                if (!store.isCurrentWirelessPairingGeneration(generation)) return@pairing
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
                    store.wirelessPairingJob = null
                }
            }
        }
        store.wirelessPairingJob = job
        job.start()
    }

    fun cancelWirelessAdbPairing() {
        val running = store.state.value.wirelessPairingRunning
        if (!running) return
        store.wirelessPairingGeneration.incrementAndGet()
        store.wirelessPairingJob?.cancel()
        store.wirelessPairingJob = null
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

    override fun close() {
        store.startNotificationPairingAfterPermission = false
        if (store.notificationPairingStartedByOwner) {
            store.notificationPairingStartedByOwner = false
            store.applicationContext?.let(PrivilegeAdbPairingService::stop)
        }
        pairingEventsJob?.cancel()
        pairingEventsJob = null
    }

    private fun maybeEnableTcpModeAfterPairing(starter: PrivilegeAdbStarter) {
        if (
            store.config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED ||
            store.currentTcpModePort() != null
        ) {
            return
        }
        runCatching {
            starter.switchToTcp(
                tcpPort = store.config.tcpPort,
            )
        }.onSuccess { result ->
            store.updateTcpModePort(result.port)
            refreshTcpModeEnabled()
        }.onFailure { throwable ->
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
        }
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
            store.currentTcpModePort() == null
        ) {
            enableTcpMode()
        }
    }
}

private fun PrivilegeUiViewModelStore.isCurrentWirelessPairingGeneration(generation: Long): Boolean =
    wirelessPairingGeneration.get() == generation

private const val DIRECT_PAIRING_PORT_DISCOVERY_TIMEOUT_MILLIS = 5_000L
