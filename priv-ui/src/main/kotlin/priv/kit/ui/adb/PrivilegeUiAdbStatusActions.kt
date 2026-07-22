package priv.kit.ui.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.Privilege
import priv.kit.core.adb.PrivilegeAdbAuthorizationStatus
import priv.kit.core.adb.PrivilegeAdbPairingCheckSession
import priv.kit.core.adb.PrivilegeAdbPairingCheckStatus
import priv.kit.core.adb.PrivilegeAdbStarter
import priv.kit.core.adb.PrivilegeAdbTcpAuthorizationCheckSession
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiAdbTcpAuthorizationStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.toPrivilegeUiDiagnosticString
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiAdbStatusActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
) : AutoCloseable {
    private val wirelessRefresh = Mutex()
    private val tcpRefresh = Mutex()
    private var pairingSession: PrivilegeAdbPairingCheckSession? = null
    private var tcpSession: PrivilegeAdbTcpAuthorizationCheckSession? = null
    private var tcpSessionPort: Int? = null

    fun refreshTcpModeEnabled() {
        coroutineScope.launch(CoroutineName("priv-ui-tcp-mode-refresh")) {
            refreshTcpModeEnabled(markChecking = true)
        }
    }

    suspend fun pollTcpModeStatus() {
        try {
            var firstRefresh = true
            while (currentCoroutineContext().isActive) {
                refreshTcpModeEnabled(markChecking = firstRefresh)
                firstRefresh = false
                delay(store.config.wirelessStatusPollIntervalMillis.milliseconds)
            }
        } finally {
            closeTcpSession()
        }
    }

    suspend fun pollWirelessAdbStatus() {
        markWirelessAdbStatusChecking()
        try {
            while (currentCoroutineContext().isActive) {
                refreshWirelessAdbStatus(markChecking = false)
                delay(store.config.wirelessStatusPollIntervalMillis.milliseconds)
            }
        } finally {
            closePairingSession()
            updateNotificationPairingRunning()
        }
    }

    suspend fun refreshAdbIdentityInfoNow() {
        runCatching {
            withContext(Dispatchers.IO) {
                Privilege.createAdbStarter(
                    adbDeviceName = store.currentAdbDeviceNameOverride(),
                ).getIdentityInfo()
            }
        }.onSuccess { info ->
            store.updateState { it.copy(adbKeyFingerprint = info.publicKeyFingerprint) }
        }.onFailure { throwable ->
            if (throwable is CancellationException) throw throwable
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
        }
    }

    fun refreshWifiConnected() {
        store.updateState { it.copy(wifiConnected = store.isWifiConnected()) }
    }

    suspend fun forceWirelessAdbStatusRefreshForAction(): Boolean {
        val refreshed = withTimeoutOrNull(actionRefreshTimeoutMillis().milliseconds) {
            refreshWirelessAdbStatus(markChecking = true)
            true
        }
        if (refreshed == null) closePairingSession()
        return refreshed ?: false
    }

    suspend fun forceTcpModeStatusRefreshForAction(): Boolean {
        val refreshed = withTimeoutOrNull(actionRefreshTimeoutMillis().milliseconds) {
            refreshTcpModeEnabled(markChecking = true)
            true
        }
        if (refreshed == null) closeTcpSession()
        return refreshed ?: false
    }

    override fun close() {
        closePairingSession()
        closeTcpSession()
    }

    fun resetWirelessPairingSession() = closePairingSession()

    private suspend fun refreshTcpModeEnabled(markChecking: Boolean): Unit = tcpRefresh.withLock {
        if (store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED) {
            store.updateTcpModePort(null)
            store.updateConfiguredTcpModePort(null)
            closeTcpSession()
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return@withLock
        }
        if (markChecking) {
            store.updateState {
                it.copy(
                    tcpAuthorizationStatus =
                        it.tcpAuthorizationStatus.checkingUnlessAuthorizedOrAuthorizing(),
                )
            }
        }
        if (shouldSkipTcpAuthorizationRefresh(store.state.value.tcpAuthorizationStatus)) return@withLock

        val starter = Privilege.createAdbStarter(
            adbDeviceName = store.currentAdbDeviceNameOverride(),
        )
        val activeTcpPort = withContext(Dispatchers.IO) {
            runCatching { starter.getActiveTcpPort() }.getOrNull()
        }
        val configuredTcpPort = withContext(Dispatchers.IO) {
            runCatching { starter.getConfiguredTcpPort() }.getOrNull()
        }
        store.updateTcpModePort(activeTcpPort)
        store.updateConfiguredTcpModePort(configuredTcpPort)
        if (configuredTcpPort == null) {
            closeTcpSession()
            store.updateState {
                it.copy(tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNKNOWN)
            }
            return@withLock
        }

        val authorization = checkTcpAuthorization(starter, configuredTcpPort)
        val previousStatus = store.state.value.tcpAuthorizationStatus
        val nextStatus = authorization.status.toUiTcpAuthorizationStatus()
        store.updateState { it.copy(tcpAuthorizationStatus = nextStatus) }
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

    private suspend fun checkTcpAuthorization(
        starter: PrivilegeAdbStarter,
        port: Int,
    ) = tcpSession
        ?.takeIf { tcpSessionPort == port }
        ?.check()
        ?.also { result ->
            if (result.status != PrivilegeAdbAuthorizationStatus.AUTHORIZED) closeTcpSession()
        }
        ?: run {
            closeTcpSession()
            val session = runCatching { starter.openTcpAuthorizationCheckSession(port) }
                .getOrElse { return@run starter.checkTcpAuthorization(port) }
            tcpSession = session
            tcpSessionPort = port
            session.check().also { result ->
                if (result.status != PrivilegeAdbAuthorizationStatus.AUTHORIZED) closeTcpSession()
            }
        }

    private suspend fun refreshWirelessAdbStatus(markChecking: Boolean): Unit =
        wirelessRefresh.withLock {
            if (markChecking) markWirelessAdbStatusChecking()
            try {
                pollWirelessAdbStatusOnce()
            } catch (exception: CancellationException) {
                throw exception
            } catch (throwable: Throwable) {
                store.updateState {
                    it.withWirelessAdbOffline(
                        managedWirelessAdbStatus =
                            it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
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

    private suspend fun pollWirelessAdbStatusOnce() {
        val wifiConnected = store.isWifiConnected()
        if (!wifiConnected) {
            closePairingSession()
            store.updateState {
                it.withWirelessAdbOffline(
                    wifiConnected = false,
                    managedWirelessAdbStatus =
                        it.managedWirelessAdbStatus.failedUnlessManagedWirelessAdbHidden(
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
        val wirelessControlStatus = withContext(Dispatchers.IO) {
            starter.getWirelessDebuggingControlStatus()
        }
        val managedWirelessStatus = if (store.config.enableManagedWirelessAdb) {
            wirelessControlStatus.toUiManagedWirelessAdbStatus()
        } else {
            PrivilegeUiManagedWirelessAdbStatus.UNKNOWN
        }
        if (!wirelessControlStatus.wirelessDebuggingEnabled) {
            closePairingSession()
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

        val connectPort = try {
            starter.discoverConnectPort(timeoutMillis)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            null
        }
        val pairingServiceOn = try {
            starter.discoverPairingPort(timeoutMillis)
            true
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Throwable) {
            false
        }
        val pairingCheck = connectPort?.let { checkWirelessPairing(starter, it, timeoutMillis) }
            ?: run {
                closePairingSession()
                null
            }

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
                    ?.takeIf(String::isNotBlank)
                    ?: it.adbKeyFingerprint,
                notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                    store.notificationPairingOwnerId,
                ),
            )
        }
    }

    private suspend fun checkWirelessPairing(
        starter: PrivilegeAdbStarter,
        port: Int,
        timeoutMillis: Long,
    ) = pairingSession
        ?.check()
        ?.also { result -> if (!result.paired) closePairingSession() }
        ?: starter.openPairingCheckSession(
            port = port,
            discoverPort = false,
            portDiscoveryTimeoutMillis = timeoutMillis,
        ).also { pairingSession = it }
            .check()
            .also { result -> if (!result.paired) closePairingSession() }

    private fun closePairingSession() {
        pairingSession?.close()
        pairingSession = null
    }

    private fun closeTcpSession() {
        tcpSession?.close()
        tcpSession = null
        tcpSessionPort = null
    }

    private fun updateNotificationPairingRunning() {
        store.updateState {
            it.copy(
                notificationPairingRunning = PrivilegeAdbPairingService.isRunning(
                    store.notificationPairingOwnerId,
                ),
            )
        }
    }

    private fun actionRefreshTimeoutMillis(): Long =
        store.config.wirelessStatusDiscoveryTimeoutMillis * STATUS_REFRESH_ACTION_WAIT_ATTEMPTS

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
        PrivilegeUiAdbTcpAuthorizationStatus = when (this) {
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
): PrivilegeUiManagedWirelessAdbStatus = when {
    !enabled -> PrivilegeUiManagedWirelessAdbStatus.UNKNOWN
    this == PrivilegeUiManagedWirelessAdbStatus.UNDECLARED -> this
    else -> PrivilegeUiManagedWirelessAdbStatus.FAILED
}

internal fun shouldAppendTcpAuthorizationFailureLog(
    previousStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    nextStatus: PrivilegeUiAdbTcpAuthorizationStatus,
    failureMessage: String?,
): Boolean = !failureMessage.isNullOrBlank() && previousStatus != nextStatus

internal fun PrivilegeAdbAuthorizationStatus.toUiTcpAuthorizationStatus():
    PrivilegeUiAdbTcpAuthorizationStatus = when (this) {
    PrivilegeAdbAuthorizationStatus.AUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
    PrivilegeAdbAuthorizationStatus.UNAUTHORIZED -> PrivilegeUiAdbTcpAuthorizationStatus.UNAUTHORIZED
    PrivilegeAdbAuthorizationStatus.UNAVAILABLE -> PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE
    PrivilegeAdbAuthorizationStatus.ERROR -> PrivilegeUiAdbTcpAuthorizationStatus.FAILED
}

internal fun shouldSkipTcpAuthorizationRefresh(
    status: PrivilegeUiAdbTcpAuthorizationStatus,
): Boolean = status == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING

private const val STATUS_REFRESH_ACTION_WAIT_ATTEMPTS = 3L
