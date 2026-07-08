package priv.kit.ui

import android.annotation.SuppressLint
import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.annotation.RestrictTo
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import priv.kit.Privilege
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingEvent
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingEventType
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingInputState
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingIntentContract
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationFactory
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingCode
import priv.kit.ui.adb.pairing.privilegeAdbRequestedDeviceName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeAdbPairingService public constructor() : LifecycleService() {
    private val sessionSerial = AtomicInteger()
    private var activeJob: Job? = null
    private var pairingPort: Int? = null
    private var requestedAdbDeviceName: String? = null
    private var pairingInputState: PrivilegeAdbPairingInputState? = null
    private lateinit var notificationFactory: PrivilegeAdbPairingNotificationFactory

    override fun onCreate() {
        super.onCreate()
        notificationFactory = PrivilegeAdbPairingNotificationFactory(this)
        notificationFactory.ensureNotificationChannel()
        activeService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = when (intent?.action) {
            PrivilegeAdbPairingIntentContract.ACTION_START -> startSearch(intent.privilegeAdbRequestedDeviceName)
            PrivilegeAdbPairingIntentContract.ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PrivilegeAdbPairingIntentContract.REMOTE_INPUT_PAIRING_CODE)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                if (!code.isPrivilegeUiPairingCode()) return START_NOT_STICKY
                val inputState = PrivilegeAdbPairingInputState.fromPairingCode(
                    code = code,
                    selectedIndex = 0,
                )
                startPairing(
                    pairingCode = code,
                    inputState = inputState,
                )
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_LEFT -> updatePairingInput {
                it.moveLeft()
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_UP -> updatePairingInput {
                it.incrementDigit()
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_DOWN -> updatePairingInput {
                it.decrementDigit()
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_RIGHT -> updatePairingInput {
                it.moveRight()
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_SUBMIT -> submitPairingInput()
            PrivilegeAdbPairingIntentContract.ACTION_STOP -> {
                stopPairing(getString(R.string.priv_ui_pairing_stopped))
                null
            }
            else -> null
        }

        if (notification != null) {
            startForegroundSafely(notification)
        }
        return START_REDELIVER_INTENT
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        pairingInputState?.let(::showInputNotification)
    }

    override fun onDestroy() {
        sessionSerial.incrementAndGet()
        activeJob?.cancel()
        activeJob = null
        clearPairingSession()
        cancelInputNotification()
        clearActiveService()
        notificationFactory.deleteNotificationChannel()
        super.onDestroy()
    }

    private fun startSearch(adbDeviceName: String?): Notification {
        val requestedDeviceName = adbDeviceName.normalizedAdbDeviceName()
        val session = startNewSession()
        pairingPort = null
        requestedAdbDeviceName = requestedDeviceName
        if (pairingInputState == null) {
            pairingInputState = PrivilegeAdbPairingInputState()
        }
        showInputNotification()
        val searchMessage = getString(R.string.priv_ui_checking_wireless_adb)
        sendPairingEvent(
            type = PrivilegeAdbPairingEventType.SEARCHING,
            message = searchMessage,
        )
        activeJob = lifecycleScope.launch {
            val starter = Privilege.createAdbStarter(adbDeviceName = requestedDeviceName)
            while (isActive && isCurrentSession(session)) {
                val retryMessage = try {
                    val (port, identityInfo) = withContext(Dispatchers.IO) {
                        val port = starter.discoverPairingPort(PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS)
                        val identityInfo = runCatching { starter.getIdentityInfo() }.getOrNull()
                        port to identityInfo
                    }
                    if (!isCurrentSession(session)) return@launch

                    val message = getString(R.string.priv_ui_pairing_service_found_text)
                    sendPairingEvent(
                        type = PrivilegeAdbPairingEventType.FOUND,
                        message = message,
                        port = port,
                        adbDeviceName = identityInfo?.identity?.deviceName,
                        fingerprint = identityInfo?.publicKeyFingerprint,
                    )
                    val state = pairingInputState ?: PrivilegeAdbPairingInputState()
                    pairingPort = port
                    requestedAdbDeviceName = requestedDeviceName
                    pairingInputState = state
                    startForegroundSafely(
                        notificationFactory.statusNotification(text = message),
                    )
                    showInputNotification()
                    return@launch
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException || !isCurrentSession(session)) {
                        return@launch
                    }
                    getString(R.string.priv_ui_pairing_search_attempt)
                }
                sendPairingEvent(type = PrivilegeAdbPairingEventType.SEARCHING, message = retryMessage)
                startForegroundSafely(notificationFactory.statusNotification(text = retryMessage))
                delay(PAIRING_DISCOVERY_RETRY_DELAY_MILLIS.milliseconds)
            }
        }
        return notificationFactory.statusNotification(text = searchMessage)
    }

    private fun updatePairingInput(
        transform: (PrivilegeAdbPairingInputState) -> PrivilegeAdbPairingInputState,
    ): Notification? {
        val state = transform(pairingInputState ?: PrivilegeAdbPairingInputState())
        pairingInputState = state
        showInputNotification(state)
        return null
    }

    private fun submitPairingInput(): Notification? {
        val state = pairingInputState ?: PrivilegeAdbPairingInputState()
        pairingInputState = state
        return startPairing(
            pairingCode = state.code,
            inputState = state,
        )
    }

    private fun submitPairingCodeFromCaller(code: String) {
        if (!code.isPrivilegeUiPairingCode()) return
        val state = PrivilegeAdbPairingInputState.fromPairingCode(
            code = code,
            selectedIndex = 0,
        )
        startPairing(
            pairingCode = code,
            inputState = state,
        )?.let(::startForegroundSafely)
    }

    private fun restartSearchForUnavailablePairingPort(): Notification {
        val message = getString(R.string.priv_ui_pairing_port_unavailable)
        sendPairingEvent(
            type = PrivilegeAdbPairingEventType.FAILED,
            message = message,
            running = true,
        )
        return startSearch(requestedAdbDeviceName)
    }

    private fun startPairing(
        pairingCode: String,
        inputState: PrivilegeAdbPairingInputState,
    ): Notification? {
        val code = pairingCode.trim()
        if (!code.isPrivilegeUiPairingCode()) return null
        val port = pairingPort?.takeIf { it in 1..65535 } ?: return restartSearchForUnavailablePairingPort()
        val adbDeviceName = requestedAdbDeviceName

        val session = startNewSession()
        pairingInputState = inputState
        showInputNotification(inputState)
        sendPairingEvent(
            type = PrivilegeAdbPairingEventType.PAIRING,
            message = getString(R.string.priv_ui_pairing_with_port),
            port = port,
        )
        activeJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Privilege.createAdbStarter(adbDeviceName = adbDeviceName)
                        .pair(pairingCode = code)
                }
                if (!isCurrentSession(session)) return@launch

                val message = getString(R.string.priv_ui_pairing_success_text)
                sendPairingEvent(
                    type = PrivilegeAdbPairingEventType.PAIRED,
                    message = message,
                    port = result.port,
                    adbDeviceName = result.identity.deviceName,
                    fingerprint = result.publicKeyFingerprint,
                )
                stopPairingService(cancelActiveJob = false)
            } catch (throwable: Throwable) {
                if (throwable is CancellationException || !isCurrentSession(session)) {
                    return@launch
                }
                val message = getString(R.string.priv_ui_pairing_failed_text, throwable.failureMessage())
                startForegroundSafely(
                    restorePairingInputAfterFailure(
                        port = port,
                        adbDeviceName = adbDeviceName,
                        state = inputState,
                        message = message,
                    ),
                )
            }
        }
        return notificationFactory.workingNotification()
    }

    private fun restorePairingInputAfterFailure(
        port: Int,
        adbDeviceName: String?,
        state: PrivilegeAdbPairingInputState,
        message: String,
    ): Notification {
        activeJob = null
        pairingPort = port
        requestedAdbDeviceName = adbDeviceName
        pairingInputState = state
        sendPairingEvent(
            type = PrivilegeAdbPairingEventType.FAILED,
            message = message,
            running = true,
            port = port,
        )
        showInputNotification(state)
        return notificationFactory.statusNotification(text = message)
    }

    private fun stopPairing(message: String) {
        sendPairingEvent(type = PrivilegeAdbPairingEventType.STOPPED, message = message)
        stopPairingService()
    }

    private fun stopPairingService(cancelActiveJob: Boolean = true) {
        sessionSerial.incrementAndGet()
        if (cancelActiveJob) {
            activeJob?.cancel()
        }
        activeJob = null
        clearPairingSession()
        cancelInputNotification()
        clearActiveService()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelStatusNotification()
        stopSelf()
    }

    private fun startNewSession(): Int {
        activeJob?.cancel()
        activeJob = null
        return sessionSerial.incrementAndGet()
    }

    private fun isCurrentSession(session: Int): Boolean =
        session == sessionSerial.get()

    private fun showInputNotification(
        state: PrivilegeAdbPairingInputState = pairingInputState ?: PrivilegeAdbPairingInputState(),
    ) {
        notifySafely(
            PrivilegeAdbPairingIntentContract.INPUT_NOTIFICATION_ID,
            notificationFactory.inputNotification(state),
        )
    }

    private fun cancelInputNotification() {
        notificationManager.cancel(PrivilegeAdbPairingIntentContract.INPUT_NOTIFICATION_ID)
    }

    private fun cancelStatusNotification() {
        notificationManager.cancel(PrivilegeAdbPairingIntentContract.NOTIFICATION_ID)
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            startForeground(PrivilegeAdbPairingIntentContract.NOTIFICATION_ID, notification)
        } catch (throwable: Throwable) {
            notifySafely(PrivilegeAdbPairingIntentContract.NOTIFICATION_ID, notification)
            sendPairingEvent(
                type = PrivilegeAdbPairingEventType.FAILED,
                message = getString(R.string.priv_ui_pairing_foreground_failed, throwable.failureMessage()),
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySafely(id: Int, notification: Notification) {
        if (!notificationManager.areNotificationsEnabled()) return
        try {
            notificationManager.notify(id, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked while the foreground service is running.
        }
    }

    private val notificationManager: NotificationManagerCompat
        get() = NotificationManagerCompat.from(this)

    private fun sendPairingEvent(
        type: PrivilegeAdbPairingEventType,
        message: String,
        port: Int? = null,
        adbDeviceName: String? = null,
        fingerprint: String? = null,
        running: Boolean = type.isRunningByDefault(),
    ) {
        pairingEventState.tryEmit(
            PrivilegeAdbPairingEvent(
                type = type,
                message = message,
                running = running,
                port = port,
                adbDeviceName = adbDeviceName,
                fingerprint = fingerprint,
            ),
        )
    }

    private fun restartSearch(adbDeviceName: String?) {
        startForegroundSafely(startSearch(adbDeviceName))
    }

    private fun stopPairingFromCaller() {
        stopPairing(getString(R.string.priv_ui_pairing_stopped))
    }

    private fun clearPairingSession() {
        pairingPort = null
        requestedAdbDeviceName = null
        pairingInputState = null
    }

    private fun clearActiveService() {
        if (activeService === this) {
            activeService = null
        }
    }

    private fun Throwable.failureMessage(): String =
        message ?: javaClass.simpleName

    private fun PrivilegeAdbPairingEventType.isRunningByDefault(): Boolean =
        this == PrivilegeAdbPairingEventType.SEARCHING ||
            this == PrivilegeAdbPairingEventType.FOUND ||
            this == PrivilegeAdbPairingEventType.PAIRING

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public companion object {
        private const val PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS = 6_000L
        private const val PAIRING_DISCOVERY_RETRY_DELAY_MILLIS = 500L

        @Volatile
        private var activeService: PrivilegeAdbPairingService? = null

        private val pairingEventState = MutableSharedFlow<PrivilegeAdbPairingEvent>(
            extraBufferCapacity = 64,
        )
        internal val pairingEvents: SharedFlow<PrivilegeAdbPairingEvent> = pairingEventState.asSharedFlow()

        internal val running: Boolean
            get() = activeService != null

        public fun start(context: Context, adbDeviceName: String?) {
            activeService?.let { service ->
                service.lifecycleScope.launch {
                    service.restartSearch(adbDeviceName)
                }
                return
            }
            val intent = Intent(context, PrivilegeAdbPairingService::class.java)
                .setAction(PrivilegeAdbPairingIntentContract.ACTION_START)
                .apply {
                    adbDeviceName.normalizedAdbDeviceName()?.let { requestedDeviceName ->
                        putExtra(PrivilegeAdbPairingIntentContract.EXTRA_REQUESTED_ADB_DEVICE_NAME, requestedDeviceName)
                    }
                }
            context.startForegroundService(intent)
        }

        public fun stop(context: Context) {
            activeService?.let { service ->
                service.lifecycleScope.launch {
                    service.stopPairingFromCaller()
                }
                return
            }
            context.startService(
                Intent(context, PrivilegeAdbPairingService::class.java)
                    .setAction(PrivilegeAdbPairingIntentContract.ACTION_STOP),
            )
        }

        internal fun submitPairingCode(context: Context, pairingCode: String) {
            val code = pairingCode.trim()
            if (!code.isPrivilegeUiPairingCode()) return
            activeService?.let { service ->
                service.lifecycleScope.launch {
                    service.submitPairingCodeFromCaller(code)
                }
                return
            }
            pairingEventState.tryEmit(
                PrivilegeAdbPairingEvent(
                    type = PrivilegeAdbPairingEventType.FAILED,
                    message = context.getString(R.string.priv_ui_notification_permission_missing),
                    running = false,
                ),
            )
        }

        private fun String?.normalizedAdbDeviceName(): String? =
            this?.takeUnless { it.isBlank() }
    }
}
