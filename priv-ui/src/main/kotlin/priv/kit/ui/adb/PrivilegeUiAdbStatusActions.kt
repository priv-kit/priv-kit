package priv.kit.ui.adb

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import priv.kit.core.Privilege
import priv.kit.core.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.core.adb.PrivilegeAdbPairingCheckStatus
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.state.PrivilegeUiNoopCloseable
import priv.kit.ui.state.PrivilegeUiPollingSlot
import priv.kit.ui.state.PrivilegeUiStatusRefreshController
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.toPrivilegeUiDiagnosticString
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

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
        onStop = {
            adbConnectionSessions.closeTcpAuthorizationCheckSession()
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
                    wifiConnected = store.isWifiConnected(),
                    wirelessDebuggingStatus = it.wirelessDebuggingStatus.checkingIfUnknown(),
                    wirelessPairingServiceStatus = it.wirelessPairingServiceStatus.checkingIfUnknown(),
                    wirelessPairingCheckStatus = privilegeUiRefreshingPairingCheckStatus(
                        wirelessDebuggingStatus = it.wirelessDebuggingStatus,
                        currentStatus = it.wirelessPairingCheckStatus,
                    ),
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.checkingIfUnknown(),
                    notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                        store.notificationPairingOwnerId,
                    ),
                )
            }
        },
        onStop = {
            adbConnectionSessions.closeWirelessPairingCheckSession()
            store.updateState {
                it.copy(
                    notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                        store.notificationPairingOwnerId,
                    ),
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
            store.updateConfiguredTcpModePort(null)
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

    suspend fun forceWirelessAdbStatusRefreshForAction(
        stop: AtomicBoolean? = null,
    ): Boolean =
        forceStatusRefreshForAction(
            controller = wirelessAdbStatusRefresh,
            stop = stop,
            refreshStop = {
                wirelessAdbStatusPolling.currentStop()?.takeUnless { it.get() } ?: AtomicBoolean(false)
            },
            refresh = { refreshStop ->
                refreshWirelessAdbStatusNow(
                    stop = refreshStop ?: AtomicBoolean(false),
                    markChecking = true,
                )
            },
            activeAfterRefresh = { refreshStop -> refreshStop?.get() != true },
        )

    suspend fun forceTcpModeStatusRefreshForAction(
        stop: AtomicBoolean? = null,
    ): Boolean =
        forceStatusRefreshForAction(
            controller = tcpModeStatusRefresh,
            stop = stop,
            refreshStop = { tcpModeStatusPolling.currentStop()?.takeUnless { it.get() } },
            refresh = { refreshStop ->
                refreshTcpModeEnabledNow(stop = refreshStop, markChecking = true)
            },
        )

    fun close() {
        stopTcpModeStatusPolling()
        stopWirelessAdbStatusPolling()
    }

    private suspend fun pollWirelessAdbStatus(stop: AtomicBoolean) {
        while (!stop.get()) {
            wirelessAdbStatusRefresh.run {
                refreshWirelessAdbStatusNow(stop = stop, markChecking = false)
            }
            if (!sleepPolling(stop, store.config.wirelessStatusPollIntervalMillis)) return
        }
    }

    private suspend fun pollTcpModeStatus(stop: AtomicBoolean) {
        var firstRefresh = true
        while (!stop.get()) {
            tcpModeStatusRefresh.run {
                refreshTcpModeEnabledNow(stop = stop, markChecking = firstRefresh)
            }
            firstRefresh = false
            if (!sleepPolling(stop, store.config.wirelessStatusPollIntervalMillis)) return
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
        val activeTcpPort = runCatching { starter.getActiveTcpPort() }.getOrNull()
        val configuredTcpPort = runCatching { starter.getConfiguredTcpPort() }.getOrNull()
        store.updateTcpModePort(activeTcpPort)
        store.updateConfiguredTcpModePort(configuredTcpPort)
        if (configuredTcpPort == null) {
            adbConnectionSessions.closeTcpAuthorizationCheckSession()
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return
        }

        val authorization = if (stop != null) {
            adbConnectionSessions.checkTcpAuthorization(
                starter = starter,
                tcpPort = configuredTcpPort,
                stop = stop,
            ) ?: return
        } else {
            starter.checkTcpAuthorization(tcpPort = configuredTcpPort)
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
                it.withWirelessAdbOffline(
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
                        enabled = store.config.enableManagedWirelessAdb,
                    ),
                    notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                        store.notificationPairingOwnerId,
                    ),
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
                notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                    store.notificationPairingOwnerId,
                ),
            )
        }
    }

    private fun pollWirelessAdbStatusOnce(stop: AtomicBoolean) {
        val wifiConnected = store.isWifiConnected()
        if (!wifiConnected) {
            adbConnectionSessions.closeWirelessPairingCheckSession()
            store.updateState {
                it.withWirelessAdbOffline(
                    wifiConnected = false,
                    managedWirelessAdbStatus = it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
                        enabled = store.config.enableManagedWirelessAdb,
                    ),
                    notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                        store.notificationPairingOwnerId,
                    ),
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
                it.withWirelessAdbOffline(
                    wifiConnected = true,
                    managedWirelessAdbStatus = managedWirelessStatus,
                    notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                        store.notificationPairingOwnerId,
                    ),
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

        store.updateState {
            it.copy(
                wifiConnected = true,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.ON,
                wirelessPairingServiceStatus = if (pairingServiceOn) {
                    PrivilegeUiWirelessAdbStatus.ON
                } else {
                    PrivilegeUiWirelessAdbStatus.OFF
                },
                wirelessPairingCheckStatus = privilegeUiPairingCheckStatus(
                    wirelessDebuggingOn = true,
                    pairingCheckPaired = pairingCheck?.toKnownPairingCheckPaired(),
                    currentStatus = it.wirelessPairingCheckStatus,
                ),
                managedWirelessAdbStatus = managedWirelessStatus,
                adbKeyFingerprint = pairingCheck
                    ?.publicKeyFingerprint
                    ?.takeIf { fingerprint -> fingerprint.isNotBlank() }
                    ?: it.adbKeyFingerprint,
                notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                    store.notificationPairingOwnerId,
                ),
            )
        }
    }

    private fun statusRefreshActionDeadlineMillis(): Long =
        System.currentTimeMillis() +
            store.config.wirelessStatusDiscoveryTimeoutMillis * STATUS_REFRESH_ACTION_WAIT_ATTEMPTS

    private fun remainingStatusRefreshActionMillis(deadline: Long): Long =
        deadline - System.currentTimeMillis()

    private suspend fun forceStatusRefreshForAction(
        controller: PrivilegeUiStatusRefreshController,
        stop: AtomicBoolean?,
        refreshStop: () -> AtomicBoolean?,
        refresh: (AtomicBoolean?) -> Unit,
        activeAfterRefresh: (AtomicBoolean?) -> Boolean = { true },
    ): Boolean {
        val deadline = statusRefreshActionDeadlineMillis()
        while (true) {
            if (stop?.get() == true) return false
            val remainingBeforeJoin = remainingStatusRefreshActionMillis(deadline)
            if (remainingBeforeJoin <= 0L) return false
            if (!controller.join(remainingBeforeJoin)) return false
            val nextStop = stop ?: refreshStop()
            if (controller.start { refresh(nextStop) }) {
                val remainingAfterStart = remainingStatusRefreshActionMillis(deadline)
                return remainingAfterStart > 0L &&
                    controller.join(remainingAfterStart) &&
                    activeAfterRefresh(nextStop)
            }
        }
    }

    private suspend fun sleepPolling(stop: AtomicBoolean, intervalMillis: Long): Boolean {
        delay(intervalMillis.milliseconds)
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

    private fun priv.kit.core.adb.PrivilegeAdbPairingCheckResult.toKnownPairingCheckPaired(): Boolean? =
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
