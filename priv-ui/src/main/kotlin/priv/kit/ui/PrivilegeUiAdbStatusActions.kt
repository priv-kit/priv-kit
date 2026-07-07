package priv.kit.ui

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import priv.kit.Privilege
import priv.kit.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.adb.PrivilegeAdbPairingCheckStatus
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUiAdbStatusActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val adbConnectionSessions: PrivilegeUiAdbConnectionSessions,
) {
    private val wirelessAdbStatusRefresh = PrivilegeUiStatusRefreshController(
        scope = coroutineScope,
        name = "priv-ui-wireless-adb-refresh",
    )
    private val tcpModeStatusRefresh = PrivilegeUiStatusRefreshController(
        scope = coroutineScope,
        name = "priv-ui-tcp-mode-refresh",
    )
    private val tcpModeStatusPolling = PrivilegeUiPollingSlot(
        scope = coroutineScope,
        name = "priv-ui-tcp-mode-status",
        onStart = {
            store.updateState { it.copy(tcpModeStatusPollingActive = true) }
        },
        onStop = {
            adbConnectionSessions.closeTcpAuthorizationCheckSession()
            store.updateState { it.copy(tcpModeStatusPollingActive = false) }
        },
    ) { stop ->
        pollTcpModeStatus(stop)
    }
    private val wirelessAdbStatusPolling = PrivilegeUiPollingSlot(
        scope = coroutineScope,
        name = "priv-ui-wireless-adb-status",
        onStart = {
            store.updateState {
                it.copy(
                    wirelessStatusPollingActive = true,
                    wifiConnected = store.isWifiConnected(),
                    wirelessDebuggingStatus = it.wirelessDebuggingStatus.checkingIfUnknown(),
                    wirelessPairingServiceStatus = it.wirelessPairingServiceStatus.checkingIfUnknown(),
                    wirelessPairingCheckStatus = privilegeUiRefreshingPairingCheckStatus(
                        wirelessDebuggingStatus = it.wirelessDebuggingStatus,
                        currentStatus = it.wirelessPairingCheckStatus,
                    ),
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.checkingIfUnknown(),
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
        },
        onStop = {
            adbConnectionSessions.closeWirelessPairingCheckSession()
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

    fun refreshTcpModeEnabled() {
        refreshTcpModeEnabled(markChecking = true)
    }

    fun refreshTcpModeEnabled(markChecking: Boolean): Boolean {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            store.updateTcpModePort(null)
            adbConnectionSessions.closeTcpAuthorizationCheckSession()
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return true
        }
        val pollingStop = tcpModeStatusPolling.currentStop()
        return tcpModeStatusRefresh.start {
            refreshTcpModeEnabledNow(stop = pollingStop, markChecking = markChecking)
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
        adbConnectionSessions.closeTcpAuthorizationCheckSession()
    }

    fun refreshAdbIdentityInfo() {
        coroutineScope.launch(Dispatchers.IO + CoroutineName("priv-ui-adb-identity")) {
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
        }
    }

    fun startWirelessAdbStatusPolling(): AutoCloseable =
        wirelessAdbStatusPolling.acquire()

    fun refreshWirelessAdbStatus() {
        val stop = wirelessAdbStatusPolling.currentStop() ?: return
        wirelessAdbStatusRefresh.start {
            refreshWirelessAdbStatusNow(stop = stop, markChecking = true)
        }
    }

    fun refreshWifiConnected() {
        store.updateState {
            it.copy(wifiConnected = store.isWifiConnected())
        }
    }

    fun stopWirelessAdbStatusPolling() {
        wirelessAdbStatusPolling.stopAll()
        adbConnectionSessions.closeWirelessPairingCheckSession()
    }

    suspend fun forceWirelessAdbStatusRefreshForAction(): Boolean {
        val deadline = statusRefreshActionDeadlineMillis()
        while (true) {
            val remainingBeforeJoin = remainingStatusRefreshActionMillis(deadline)
            if (remainingBeforeJoin <= 0L) return false
            if (!wirelessAdbStatusRefresh.join(remainingBeforeJoin)) return false
            val stop = wirelessAdbStatusPolling.currentStop()?.takeUnless { it.get() } ?: AtomicBoolean(false)
            if (wirelessAdbStatusRefresh.start { refreshWirelessAdbStatusNow(stop = stop, markChecking = true) }) {
                val remainingAfterStart = remainingStatusRefreshActionMillis(deadline)
                return remainingAfterStart > 0L &&
                    wirelessAdbStatusRefresh.join(remainingAfterStart) &&
                    !stop.get()
            }
        }
    }

    suspend fun forceTcpModeStatusRefreshForAction(): Boolean {
        val deadline = statusRefreshActionDeadlineMillis()
        while (true) {
            val remainingBeforeJoin = remainingStatusRefreshActionMillis(deadline)
            if (remainingBeforeJoin <= 0L) return false
            if (!tcpModeStatusRefresh.join(remainingBeforeJoin)) return false
            val stop = tcpModeStatusPolling.currentStop()?.takeUnless { it.get() }
            if (tcpModeStatusRefresh.start { refreshTcpModeEnabledNow(stop = stop, markChecking = true) }) {
                val remainingAfterStart = remainingStatusRefreshActionMillis(deadline)
                return remainingAfterStart > 0L && tcpModeStatusRefresh.join(remainingAfterStart)
            }
        }
    }

    fun close() {
        stopTcpModeStatusPolling()
        stopWirelessAdbStatusPolling()
    }

    private suspend fun pollWirelessAdbStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            wirelessAdbStatusRefresh.run {
                refreshWirelessAdbStatusNow(stop = stop, markChecking = false)
            }
            if (!sleepWirelessAdbPolling(stop)) return
        }
    }

    private suspend fun pollTcpModeStatus(stop: AtomicBoolean) {
        var firstRefresh = true
        while (!stop.get()) {
            tcpModeStatusRefresh.run {
                refreshTcpModeEnabledNow(stop = stop, markChecking = firstRefresh)
            }
            firstRefresh = false
            if (!sleepTcpModeStatusPolling(stop)) return
        }
    }

    private fun refreshTcpModeEnabledNow(
        stop: AtomicBoolean?,
        markChecking: Boolean,
    ) {
        if (markChecking) {
            store.updateState {
                it.copy(tcpAuthorizationStatus = it.tcpAuthorizationStatus.checkingUnlessAuthorizedOrAuthorizing())
            }
        }
        refreshTcpModeAndAuthorization(stop)
    }

    private fun refreshTcpModeAndAuthorization(stop: AtomicBoolean?) {
        if (shouldSkipTcpAuthorizationRefresh(store.state.value.tcpAuthorizationStatus)) {
            return
        }
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val activeTcpPort = runCatching {
            starter.getActiveTcpPort()
        }.getOrNull()
        store.updateTcpModePort(activeTcpPort)
        if (activeTcpPort == null) {
            adbConnectionSessions.closeTcpAuthorizationCheckSession()
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return
        }

        val authorization = if (stop != null) {
            adbConnectionSessions.checkTcpAuthorization(
                starter = starter,
                tcpPort = activeTcpPort,
                stop = stop,
            ) ?: return
        } else {
            starter.checkTcpAuthorization(tcpPort = activeTcpPort)
        }
        val previousStatus = store.state.value.tcpAuthorizationStatus
        val nextStatus = authorization.status.toUiTcpAuthorizationStatus()
        store.updateState {
            it.copy(tcpAuthorizationStatus = nextStatus)
        }
        if (
            shouldAppendTcpAuthorizationFailureLog(
                previousStatus = previousStatus,
                nextStatus = nextStatus,
                failureMessage = authorization.failureMessage,
            )
        ) {
            authorization.failureMessage?.let(store::appendLog)
        }
    }

    private fun refreshWirelessAdbStatusNow(
        stop: AtomicBoolean,
        markChecking: Boolean,
    ) {
        if (markChecking) {
            markWirelessAdbStatusChecking()
        }
        runCatching {
            pollWirelessAdbStatusOnce(stop)
        }.onFailure { throwable ->
            if (stop.get()) return@onFailure
            store.updateState {
                it.copy(
                    wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
                        enabled = store.config.enableManagedWirelessAdb,
                    ),
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }

    private fun markWirelessAdbStatusChecking() {
        val wifiConnected = store.isWifiConnected()
        store.updateState {
            it.copy(
                wifiConnected = wifiConnected,
                wirelessDebuggingStatus = if (wifiConnected) {
                    it.wirelessDebuggingStatus.checkingUnlessOn()
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingServiceStatus = if (wifiConnected) {
                    it.wirelessPairingServiceStatus.checkingUnlessOn()
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingCheckStatus = if (wifiConnected) {
                    privilegeUiRefreshingPairingCheckStatus(
                        wirelessDebuggingStatus = it.wirelessDebuggingStatus,
                        currentStatus = it.wirelessPairingCheckStatus,
                    )
                } else {
                    PrivilegeUiWirelessAdbStatus.UNKNOWN
                },
                managedWirelessAdbStatus = it.managedWirelessAdbStatus.checkingIfUnknown(),
                notificationPairingRunning = PrivilegeAdbPairingService.running,
            )
        }
    }

    private fun pollWirelessAdbStatusOnce(stop: AtomicBoolean) {
        val wifiConnected = store.isWifiConnected()
        if (!wifiConnected) {
            adbConnectionSessions.closeWirelessPairingCheckSession()
            store.updateState {
                it.copy(
                    wifiConnected = false,
                    wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
                        enabled = store.config.enableManagedWirelessAdb,
                    ),
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
            return
        }
        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val timeoutMillis = store.config.wirelessStatusDiscoveryTimeoutMillis
        val wirelessControlStatus = starter.getWirelessDebuggingControlStatus()
        val managedWirelessStatus = if (store.config.enableManagedWirelessAdb) {
            wirelessControlStatus.toUiManagedWirelessAdbStatus()
        } else {
            PrivilegeUiManagedWirelessAdbStatus.UNKNOWN
        }
        if (!wirelessControlStatus.wirelessDebuggingEnabled) {
            adbConnectionSessions.closeWirelessPairingCheckSession()
            store.updateState {
                it.copy(
                    wifiConnected = true,
                    wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
                    wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                    managedWirelessAdbStatus = managedWirelessStatus,
                    notificationPairingRunning = PrivilegeAdbPairingService.running,
                )
            }
            return
        }
        val connectPort = runCatching {
            starter.discoverConnectPort(timeoutMillis)
        }.getOrNull()
        if (stop.get()) return
        val pairingServiceOn = runCatching {
            starter.discoverPairingPort(timeoutMillis)
        }.isSuccess
        if (stop.get()) return
        val pairingCheck = if (connectPort != null) {
            adbConnectionSessions.checkWirelessAdbPairing(
                starter = starter,
                connectPort = connectPort,
                timeoutMillis = timeoutMillis,
                stop = stop,
            )
        } else {
            adbConnectionSessions.closeWirelessPairingCheckSession()
            null
        }
        if (stop.get()) return
        val wirelessDebuggingOn = wirelessControlStatus.wirelessDebuggingEnabled

        store.updateState {
            it.copy(
                wifiConnected = true,
                wirelessDebuggingStatus = privilegeUiWirelessDebuggingStatus(
                    wirelessDebuggingEnabled = wirelessDebuggingOn,
                    connectPortAvailable = connectPort != null,
                    pairingServiceOn = pairingServiceOn,
                ),
                wirelessPairingServiceStatus = if (pairingServiceOn) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingCheckStatus = privilegeUiPairingCheckStatus(
                    wirelessDebuggingOn = wirelessDebuggingOn,
                    pairingCheckPaired = pairingCheck?.toKnownPairingCheckPaired(),
                    currentStatus = it.wirelessPairingCheckStatus,
                ),
                managedWirelessAdbStatus = managedWirelessStatus,
                adbKeyFingerprint = pairingCheck
                    ?.publicKeyFingerprint
                    ?.takeIf { fingerprint -> fingerprint.isNotBlank() }
                    ?: it.adbKeyFingerprint,
                notificationPairingRunning = PrivilegeAdbPairingService.running,
            )
        }
    }

    private fun statusRefreshActionDeadlineMillis(): Long =
        System.currentTimeMillis() +
            store.config.wirelessStatusDiscoveryTimeoutMillis * STATUS_REFRESH_ACTION_WAIT_ATTEMPTS

    private fun remainingStatusRefreshActionMillis(deadline: Long): Long =
        deadline - System.currentTimeMillis()

    private suspend fun sleepWirelessAdbPolling(stop: AtomicBoolean): Boolean {
        delay(store.config.wirelessStatusPollIntervalMillis)
        return !stop.get()
    }

    private suspend fun sleepTcpModeStatusPolling(stop: AtomicBoolean): Boolean {
        delay(store.config.wirelessStatusPollIntervalMillis)
        return !stop.get()
    }

    private fun PrivilegeUiWirelessAdbStatus.checkingUnlessOn(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.ON) this else PrivilegeUiWirelessAdbStatus.CHECKING

    private fun PrivilegeUiWirelessAdbStatus.checkingIfUnknown(): PrivilegeUiWirelessAdbStatus =
        if (this == PrivilegeUiWirelessAdbStatus.UNKNOWN) PrivilegeUiWirelessAdbStatus.CHECKING else this

    private fun PrivilegeUiManagedWirelessAdbStatus.checkingIfUnknown(): PrivilegeUiManagedWirelessAdbStatus =
        if (this == PrivilegeUiManagedWirelessAdbStatus.UNKNOWN) {
            PrivilegeUiManagedWirelessAdbStatus.CHECKING
        } else {
            this
        }

    private fun PrivilegeUiAdbTcpAuthorizationStatus.checkingUnlessAuthorizedOrAuthorizing():
        PrivilegeUiAdbTcpAuthorizationStatus =
        when (this) {
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED,
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            -> this
            else -> PrivilegeUiAdbTcpAuthorizationStatus.CHECKING
        }

    private fun priv.kit.adb.PrivilegeAdbPairingCheckResult.toKnownPairingCheckPaired(): Boolean? =
        when (status) {
            PrivilegeAdbPairingCheckStatus.PAIRED -> true
            PrivilegeAdbPairingCheckStatus.UNPAIRED -> false
            PrivilegeAdbPairingCheckStatus.UNAVAILABLE,
            PrivilegeAdbPairingCheckStatus.ERROR,
            -> null
        }

}

private fun PrivilegeUiManagedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
    enabled: Boolean,
): PrivilegeUiManagedWirelessAdbStatus =
    when {
        !enabled -> PrivilegeUiManagedWirelessAdbStatus.UNKNOWN
        this == PrivilegeUiManagedWirelessAdbStatus.UNDECLARED -> this
        else -> PrivilegeUiManagedWirelessAdbStatus.FAILED
    }

internal fun shouldAppendTcpAuthorizationFailureLog(
    previousStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    nextStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    failureMessage: String?,
): Boolean =
    !failureMessage.isNullOrBlank() && previousStatus != nextStatus

internal fun PrivilegeAdbAuthorizationStatus.toUiTcpAuthorizationStatus(): PrivilegeUiAdbTcpAuthorizationStatus =
    when (this) {
        PrivilegeAdbAuthorizationStatus.AUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
        PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
        PrivilegeAdbAuthorizationStatus.UNAVAILABLE -> PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE
        PrivilegeAdbAuthorizationStatus.ERROR -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
    }

internal fun shouldSkipTcpAuthorizationRefresh(
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): Boolean =
    status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING

private const val STATUS_REFRESH_ACTION_WAIT_ATTEMPTS = 3L
