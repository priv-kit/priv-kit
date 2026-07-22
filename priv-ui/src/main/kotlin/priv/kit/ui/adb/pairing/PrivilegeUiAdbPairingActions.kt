package priv.kit.ui.adb.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.shared.PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST
import priv.kit.shared.isPrivilegeAdbPort
import priv.kit.ui.PrivilegeAdbPairingService
import priv.kit.ui.PrivilegeUiAdbPairingStatus
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiPermissionState
import priv.kit.ui.PrivilegeUiWirelessAdbStatus
import priv.kit.ui.R
import priv.kit.ui.isPrivilegeUiNotificationPermissionSupported
import priv.kit.ui.toPrivilegeUiPairingCodeDigits
import priv.kit.ui.adb.currentTcpModePort
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.state.PrivilegeUiFailureKind
import priv.kit.ui.state.PrivilegeUiViewModelStore
import priv.kit.ui.state.toPrivilegeUiDiagnosticString
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiAdbPairingActions(
    private val store: PrivilegeUiViewModelStore,
    private val coroutineScope: CoroutineScope,
    private val enableTcpMode: suspend () -> Unit,
    private val acquireInteractivePermit: () -> AutoCloseable? =
        PrivilegeUiStartGate.newInteractivePermitAcquirer(),
    private val hasInteractionHost: () -> Boolean = { true },
) : AutoCloseable {
    private val pairingPermitLock = Any()
    private var pairingPermit: AutoCloseable? = null
    private var notificationEventsJob: Job? = null
    private var pairingJob: Job? = null
    private var pairingSessionSerial: Int = 0
    private var pairingPort: Int? = null
    private var requestedAdbDeviceName: String? = null
    private val notificationOwnerId: String
        get() = store.notificationPairingOwnerId

    fun observePairingNotificationEvents() {
        notificationEventsJob?.cancel()
        notificationEventsJob = coroutineScope.launch(
            Dispatchers.Main.immediate + CoroutineName("priv-ui-pairing-notification-events"),
        ) {
            PrivilegeAdbPairingService.notificationEvents.collect { event ->
                if (event.ownerId == notificationOwnerId) {
                    handleNotificationEvent(event)
                }
            }
        }
    }

    fun updatePairingCode(value: String) {
        val recoveringFromFailure = store.state.value.pairingStatus == PrivilegeUiAdbPairingStatus.FAILED
        store.updateState { current ->
            if (!recoveringFromFailure) {
                current.copy(pairingCode = value.toPrivilegeUiPairingCodeDigits())
            } else {
                val serviceFound = pairingPort != null
                current.copy(
                    pairingCode = value.toPrivilegeUiPairingCodeDigits(),
                    pairingStatus = if (serviceFound) {
                        PrivilegeUiAdbPairingStatus.FOUND
                    } else {
                        PrivilegeUiAdbPairingStatus.SEARCHING
                    },
                    pairingMessage = store.text(
                        if (serviceFound) {
                            R.string.priv_ui_pairing_service_found_text
                        } else {
                            R.string.priv_ui_pairing_search_text
                        },
                    ),
                )
            }
        }
        if (recoveringFromFailure) {
            PrivilegeAdbPairingService.updateStatus(notificationOwnerId, store.state.value.pairingMessage)
        }
    }

    suspend fun startNotificationPairing(
        requestNotificationPermission: suspend () -> PrivilegeUiPermissionState? = { null },
    ) {
        if (PrivilegeUiStartGate.isSilentStartInProgress) return
        if (store.state.value.pairingNotificationPermissionWarningVisible) {
            return
        }
        if (!ensurePairingPermit()) return
        val context = store.requireContext()
        if (!isNotificationPermissionGranted()) {
            resetPairingSessionForNotificationPermission()
            PrivilegeAdbPairingService.stop(context, notificationOwnerId)
            val permissionState = try {
                requestNotificationPermission()
            } catch (throwable: Throwable) {
                cancelNotificationPermissionRequest()
                throw throwable
            }
            continueAfterNotificationPermission(permissionState)
            return
        }
        startPairingWithNotification()
    }

    fun stopNotificationPairing() {
        stopPairingSession(
            message = store.text(R.string.priv_ui_pairing_stopped),
            stopNotification = true,
        )
    }

    fun cancelPendingPairingStart() {
        cancelNotificationPermissionRequest()
    }

    fun cancelNotificationPermissionRequest() {
        store.updateState {
            it.copy(pairingNotificationPermissionWarningVisible = false)
        }
        if (!store.state.value.pairingStatus.isPrivilegeUiPairingSessionActive()) {
            releasePairingPermit()
        }
    }

    fun cancelPairingWithoutInteractionHost(
        notificationLost: Boolean = false,
    ) {
        if (!notificationLost && PrivilegeAdbPairingService.isRequested(notificationOwnerId)) return
        stopPairingSession(
            message = store.text(R.string.priv_ui_pairing_stopped),
            stopNotification = true,
        )
    }

    fun continuePairingWithoutNotification() {
        if (!store.state.value.pairingNotificationPermissionWarningVisible) return
        if (!ensurePairingPermit()) return
        store.updateState {
            it.copy(pairingNotificationPermissionWarningVisible = false)
        }
        startPairingSession()
    }

    fun continuePendingPairingIfNotificationPermissionGranted() {
        if (!store.state.value.pairingNotificationPermissionWarningVisible) return
        if (!isNotificationPermissionGranted()) return
        if (!ensurePairingPermit()) return
        startPairingWithNotification()
    }

    fun closePairingDialog() {
        store.updateState {
            it.copy(
                pairingCode = "",
                pairingDialogVisible = false,
            )
        }
    }

    fun submitNotificationPairingCode() {
        submitPairingCode(store.state.value.pairingCode)
    }

    private fun continueAfterNotificationPermission(permissionState: PrivilegeUiPermissionState?) {
        if (permissionState == null) {
            if (!store.state.value.pairingStatus.isPrivilegeUiPairingSessionActive()) {
                releasePairingPermit()
            }
            return
        }
        if (!ensurePairingPermit()) {
            store.updateState {
                it.copy(pairingNotificationPermissionWarningVisible = true)
            }
            return
        }

        when (permissionState) {
            PrivilegeUiPermissionState.Granted -> {
                startPairingWithNotification()
            }
            PrivilegeUiPermissionState.NotGranted.Denied -> {
                startPairingSession()
                store.showFailure(PrivilegeUiFailureKind.NOTIFICATION_PERMISSION_REQUIRED)
            }
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied -> {
                store.updateState {
                    it.copy(pairingNotificationPermissionWarningVisible = true)
                }
            }
        }
    }

    override fun close() {
        store.updateState {
            it.copy(pairingNotificationPermissionWarningVisible = false)
        }
        invalidatePairingSession()
        store.applicationContext?.let { context ->
            PrivilegeAdbPairingService.stop(context, notificationOwnerId)
        }
        notificationEventsJob?.cancel()
        notificationEventsJob = null
        releasePairingPermit()
    }

    private fun resetPairingSessionForNotificationPermission() {
        invalidatePairingSession()
        store.updateState {
            it.copy(
                notificationPairingRunning = false,
                pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                pairingDialogVisible = false,
                pairingNotificationPermissionWarningVisible = false,
                pairingCode = "",
            )
        }
    }

    private fun startPairingSession() {
        if (!ensurePairingPermit()) return
        val adbDeviceName = store.currentAdbDeviceNameOverride()
        val session = startNewPairingSession()
        pairingPort = null
        requestedAdbDeviceName = adbDeviceName
        val searchMessage = store.text(R.string.priv_ui_pairing_search_text)
        store.updateState {
            it.copy(
                pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                pairingMessage = searchMessage,
                pairingDialogVisible = true,
                pairingNotificationPermissionWarningVisible = false,
                notificationPairingRunning = false,
            )
        }
        store.appendLog(store.text(R.string.priv_ui_notification_pairing_started))
        PrivilegeAdbPairingService.updateStatus(notificationOwnerId, searchMessage)
        launchPairingDiscovery(
            session = session,
            adbDeviceName = adbDeviceName,
        )
    }

    private fun startPairingWithNotification() {
        if (!ensurePairingPermit()) return
        startPairingSession()
        startNotificationUi()
    }

    private fun isNotificationPermissionGranted(): Boolean =
        !isPrivilegeUiNotificationPermissionSupported() ||
            ContextCompat.checkSelfPermission(
                store.requireContext(),
                Manifest.permission.POST_NOTIFICATIONS,
            ) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchPairingDiscovery(
        session: Int,
        adbDeviceName: String?,
        initialPort: Int? = null,
    ) {
        val job = createPairingJob("priv-ui-pairing-session") discovery@{
            val starter = Privilege.createAdbStarter(adbDeviceName = adbDeviceName)
            var monitoredPort = initialPort
            while (isActive && isCurrentPairingSession(session)) {
                if (monitoredPort == null) {
                    val discovery = try {
                        val port = starter.discoverPairingPort(PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS)
                        val identityInfo = withContext(Dispatchers.IO) {
                            runCatching { starter.getIdentityInfo() }.getOrNull()
                        }
                        port to identityInfo
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException || !isCurrentPairingSession(session)) {
                            return@discovery
                        }
                        val retryMessage = store.text(R.string.priv_ui_pairing_search_attempt)
                        updatePairingStatus(
                            status = PrivilegeUiAdbPairingStatus.SEARCHING,
                            message = retryMessage,
                        )
                        delay(PAIRING_DISCOVERY_RETRY_DELAY_MILLIS.milliseconds)
                        continue
                    }

                    if (!isCurrentPairingSession(session)) return@discovery
                    val (port, identityInfo) = discovery
                    val transition = privilegeAdbPairingDiscoveryTransition(pairingPort, port)
                    pairingPort = port
                    requestedAdbDeviceName = adbDeviceName
                    monitoredPort = port
                    if (transition == PrivilegeUiAdbPairingStatus.FOUND) {
                        val message = store.text(R.string.priv_ui_pairing_service_found_text)
                        updatePairingStatus(
                            status = transition,
                            message = message,
                            fingerprint = identityInfo?.publicKeyFingerprint,
                        )
                        store.appendLog(message)
                    }
                }

                while (isActive && isCurrentPairingSession(session)) {
                    val port = monitoredPort
                    val portListening = runInterruptible(Dispatchers.IO) {
                        isLocalPairingPortListening(port)
                    }
                    if (!portListening) break
                    delay(PAIRING_PORT_MONITOR_INTERVAL_MILLIS.milliseconds)
                }
                if (!isCurrentPairingSession(session)) return@discovery

                val transition = privilegeAdbPairingDiscoveryTransition(pairingPort, null)
                pairingPort = null
                monitoredPort = null
                if (transition == PrivilegeUiAdbPairingStatus.SEARCHING) {
                    updatePairingStatus(
                        status = transition,
                        message = store.text(R.string.priv_ui_pairing_search_text),
                    )
                }
                delay(PAIRING_DISCOVERY_RETRY_DELAY_MILLIS.milliseconds)
            }
        }
        pairingJob = job
        job.start()
    }

    private fun submitPairingCode(pairingCode: String) {
        if (!store.state.value.pairingStatus.isPrivilegeUiPairingSessionActive()) return
        if (!ensurePairingPermit()) return
        val code = pairingCode.trim()
        if (!code.isPrivilegeUiPairingCode()) {
            val message = store.text(R.string.priv_ui_pairing_code_required)
            updatePairingStatus(
                status = PrivilegeUiAdbPairingStatus.FAILED,
                message = message,
            )
            store.showFailure(PrivilegeUiFailureKind.PAIRING_CODE_REQUIRED)
            return
        }
        val port = pairingPort?.takeIf { it.isPrivilegeAdbPort() }
        if (port == null) {
            val message = store.text(R.string.priv_ui_pairing_port_unavailable)
            updatePairingStatus(
                status = PrivilegeUiAdbPairingStatus.SEARCHING,
                message = message,
            )
            store.showFailure(PrivilegeUiFailureKind.PAIRING_PORT_UNAVAILABLE)
            return
        }

        val adbDeviceName = requestedAdbDeviceName
        val session = startNewPairingSession()
        val pairingMessage = store.text(R.string.priv_ui_pairing_with_port)
        updatePairingStatus(
            status = PrivilegeUiAdbPairingStatus.PAIRING,
            message = pairingMessage,
        )
        store.appendLog(pairingMessage)
        val job = createPairingJob("priv-ui-pairing-submit") submit@{
            try {
                val result = Privilege.createAdbStarter(adbDeviceName = adbDeviceName).pair(
                    pairingCode = code,
                    port = port,
                    discoverPort = false,
                )
                if (!isCurrentPairingSession(session)) return@submit

                pairingSessionSerial += 1
                pairingJob = null
                pairingPort = null
                requestedAdbDeviceName = null
                val message = store.text(R.string.priv_ui_pairing_success_text)
                store.updateState {
                    it.copy(
                        notificationPairingRunning = false,
                        pairingStatus = PrivilegeUiAdbPairingStatus.PAIRED,
                        pairingMessage = message,
                        pairingDialogVisible = false,
                        adbKeyFingerprint = result.publicKeyFingerprint,
                        wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.ON,
                        pairingCode = "",
                    )
                }
                store.appendLog(message)
                PrivilegeAdbPairingService.updateStatus(notificationOwnerId, message)
                PrivilegeAdbPairingService.stop(store.requireContext(), notificationOwnerId)
                releasePairingPermit()
                enableTcpModeAfterPairing()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || !isCurrentPairingSession(session)) {
                    return@submit
                }
                pairingJob = null
                pairingPort = port
                requestedAdbDeviceName = adbDeviceName
                val failureKind = PrivilegeUiFailureKind.PAIRING_FAILED
                val message = store.text(failureKind.messageResId)
                updatePairingStatus(
                    status = PrivilegeUiAdbPairingStatus.FAILED,
                    message = message,
                )
                store.appendLog(throwable.toPrivilegeUiDiagnosticString())
                store.showFailure(failureKind)
                launchPairingDiscovery(
                    session = session,
                    adbDeviceName = adbDeviceName,
                    initialPort = port,
                )
            }
        }
        pairingJob = job
        job.start()
    }

    private fun startNotificationUi() {
        val context = store.requireContext()
        val message = store.state.value.pairingMessage.ifBlank {
            store.text(R.string.priv_ui_pairing_search_text)
        }
        var startFailure: Throwable? = null
        val started = try {
            PrivilegeAdbPairingService.start(
                context = context,
                ownerId = notificationOwnerId,
                statusText = message,
            )
        } catch (throwable: Throwable) {
            startFailure = throwable
            store.showFailure(PrivilegeUiFailureKind.PAIRING_NOTIFICATION_FAILED)
            store.appendLog(throwable.toPrivilegeUiDiagnosticString())
            false
        }
        store.updateState { it.copy(notificationPairingRunning = started) }
        if (!started && startFailure == null) {
            store.showFailure(PrivilegeUiFailureKind.NOTIFICATION_PERMISSION_REQUIRED)
        }
    }

    private fun stopPairingSession(
        message: String,
        stopNotification: Boolean,
    ) {
        val wasActive = store.state.value.pairingStatus.isPrivilegeUiPairingSessionActive()
        invalidatePairingSession()
        if (stopNotification) {
            PrivilegeAdbPairingService.stop(store.requireContext(), notificationOwnerId)
        }
        store.updateState {
            it.copy(
                notificationPairingRunning = false,
                pairingStatus = PrivilegeUiAdbPairingStatus.NOT_PAIRED,
                pairingMessage = message,
                pairingDialogVisible = false,
                pairingNotificationPermissionWarningVisible = false,
                pairingCode = "",
            )
        }
        if (wasActive) {
            store.appendLog(message)
        }
        releasePairingPermit()
    }

    fun handleNotificationEvent(event: PrivilegeAdbPairingNotificationEvent) {
        if (event.ownerId != notificationOwnerId) return
        when (event) {
            is PrivilegeAdbPairingNotificationEvent.Submit -> {
                updatePairingCode(event.pairingCode)
                submitPairingCode(event.pairingCode)
            }
            is PrivilegeAdbPairingNotificationEvent.Unavailable -> {
                store.updateState { it.copy(notificationPairingRunning = false) }
                store.showFailure(event.reason.toPrivilegeUiFailureKind())
                stopIfNotificationLostWithoutInteractionHost()
            }
            is PrivilegeAdbPairingNotificationEvent.Stop -> {
                stopPairingSession(
                    message = store.text(R.string.priv_ui_pairing_stopped),
                    stopNotification = false,
                )
            }
            is PrivilegeAdbPairingNotificationEvent.Detached -> {
                store.updateState { it.copy(notificationPairingRunning = false) }
                stopIfNotificationLostWithoutInteractionHost()
            }
        }
    }

    private fun stopIfNotificationLostWithoutInteractionHost() {
        if (!hasInteractionHost()) {
            cancelPairingWithoutInteractionHost(notificationLost = true)
        }
    }

    private fun updatePairingStatus(
        status: PrivilegeUiAdbPairingStatus,
        message: String,
        fingerprint: String? = null,
    ) {
        store.updateState {
            it.copy(
                pairingStatus = status,
                pairingMessage = message,
                adbKeyFingerprint = fingerprint ?: it.adbKeyFingerprint,
            )
        }
        PrivilegeAdbPairingService.updateStatus(notificationOwnerId, message)
    }

    private fun startNewPairingSession(): Int {
        pairingJob?.cancel()
        pairingJob = null
        pairingSessionSerial += 1
        return pairingSessionSerial
    }

    private fun invalidatePairingSession() {
        pairingSessionSerial += 1
        pairingJob?.cancel()
        pairingJob = null
        pairingPort = null
        requestedAdbDeviceName = null
    }

    private fun isCurrentPairingSession(session: Int): Boolean =
        session == pairingSessionSerial

    private suspend fun enableTcpModeAfterPairing() {
        if (
            !PrivilegeUiStartGate.isSilentStartInProgress &&
            store.config.adbTcpPolicy == PrivilegeUiAdbTcpPolicy.AUTO_ENABLE_AFTER_WIRELESS_PAIRED &&
            store.currentTcpModePort() == null
        ) {
            enableTcpMode()
        }
    }

    private fun ensurePairingPermit(): Boolean = synchronized(pairingPermitLock) {
        if (pairingPermit != null) return@synchronized true
        val acquired = acquireInteractivePermit() ?: return@synchronized false
        pairingPermit = acquired
        true
    }

    private fun createPairingJob(
        name: String,
        block: suspend CoroutineScope.() -> Unit,
    ): Job = coroutineScope.launch(
        context = CoroutineName(name),
        start = CoroutineStart.LAZY,
        block = block,
    )

    private fun releasePairingPermit() {
        val permit = synchronized(pairingPermitLock) {
            pairingPermit.also { pairingPermit = null }
        }
        permit?.close()
    }

    private fun isLocalPairingPortListening(port: Int): Boolean =
        try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST, port), 1)
            }
            false
        } catch (_: IOException) {
            true
        }

}

internal fun privilegeAdbPairingDiscoveryTransition(
    previousPort: Int?,
    observedPort: Int?,
): PrivilegeUiAdbPairingStatus? = when {
    previousPort == observedPort -> null
    observedPort == null -> PrivilegeUiAdbPairingStatus.SEARCHING
    else -> PrivilegeUiAdbPairingStatus.FOUND
}

internal fun PrivilegeUiAdbPairingStatus.isPrivilegeUiPairingSessionActive(): Boolean =
    this == PrivilegeUiAdbPairingStatus.SEARCHING ||
        this == PrivilegeUiAdbPairingStatus.FOUND ||
        this == PrivilegeUiAdbPairingStatus.PAIRING ||
        this == PrivilegeUiAdbPairingStatus.FAILED

private const val PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS = 6_000L
private const val PAIRING_DISCOVERY_RETRY_DELAY_MILLIS = 500L
private const val PAIRING_PORT_MONITOR_INTERVAL_MILLIS = 500L
