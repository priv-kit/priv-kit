package priv.kit.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RestrictTo
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingInputState
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingIntentContract
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationFactory
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationUnavailableReason
import priv.kit.ui.adb.pairing.isPrivilegeUiPairingCode
import priv.kit.ui.adb.pairing.toPrivilegeUiFailureKind

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeAdbPairingService public constructor() : LifecycleService() {
    private var pairingInputState = PrivilegeAdbPairingInputState()
    private var notificationOwnerId: String? = null
    private var lastRemoteInputSubmissionElapsedRealtime: Long = NO_REMOTE_INPUT_SUBMISSION
    private var pendingStopOwnerId: String? = null
    private lateinit var notificationFactory: PrivilegeAdbPairingNotificationFactory
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingStopRunnable = Runnable {
        val ownerId = pendingStopOwnerId
        pendingStopOwnerId = null
        if (ownerId != null && notificationOwnerId == ownerId) {
            stopNotificationService()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationFactory = PrivilegeAdbPairingNotificationFactory(this)
        notificationFactory.ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val notification = when (intent?.action) {
            PrivilegeAdbPairingIntentContract.ACTION_START -> {
                val ownerId = intent.getStringExtra(
                    PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_OWNER_ID,
                ) ?: latestOwnerId
                if (ownerId.isNullOrBlank()) {
                    stopNotificationService()
                    null
                } else {
                    attachOwner(ownerId)
                    if (ensureNotificationUiAvailable()) showPairingNotifications() else null
                }
            }
            PrivilegeAdbPairingIntentContract.ACTION_REPLY -> {
                submitPairingCode(
                    code = RemoteInput.getResultsFromIntent(intent)
                        ?.getCharSequence(PrivilegeAdbPairingIntentContract.REMOTE_INPUT_PAIRING_CODE)
                        ?.toString()
                        ?.trim()
                        .orEmpty(),
                    submittedViaRemoteInput = true,
                )
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_LEFT -> {
                updatePairingInput { it.moveLeft() }
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_UP -> {
                updatePairingInput { it.incrementDigit() }
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_DOWN -> {
                updatePairingInput { it.decrementDigit() }
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_RIGHT -> {
                updatePairingInput { it.moveRight() }
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_SUBMIT -> {
                submitPairingCode(
                    code = pairingInputState.code,
                    submittedViaRemoteInput = false,
                )
                null
            }
            PrivilegeAdbPairingIntentContract.ACTION_STOP -> {
                notificationOwnerId?.let { ownerId ->
                    notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Stop(ownerId))
                }
                stopNotificationService()
                null
            }
            else -> null
        }
        notification?.let(::startForegroundSafely)
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        notificationFactory.ensureNotificationChannel()
        showPairingNotifications()?.let(::startForegroundSafely)
    }

    override fun onDestroy() {
        val detachedOwnerId = notificationOwnerId
        notificationOwnerId = null
        cancelPendingStop()
        cancelInputNotification()
        cancelStatusNotification()
        clearActiveService(detachedOwnerId)
        detachedOwnerId?.let { ownerId ->
            notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Detached(ownerId))
        }
        super.onDestroy()
    }

    private fun attachOwner(ownerId: String) {
        cancelPendingStop()
        lastRemoteInputSubmissionElapsedRealtime = NO_REMOTE_INPUT_SUBMISSION
        val previousOwnerId = notificationOwnerId
        if (previousOwnerId != null && previousOwnerId != ownerId) {
            notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Detached(previousOwnerId))
            pairingInputState = PrivilegeAdbPairingInputState()
        }
        notificationOwnerId = ownerId
        latestOwnerId = ownerId
        activeService = this
    }

    private fun showPairingNotifications(): Notification? {
        if (notificationOwnerId == null || !showInputNotification()) return null
        val text = (latestStatusText ?: privilegeUiText(R.string.priv_ui_pairing_search_text))
            .asString(this)
        return if (latestAcceptsPairingCode) {
            notificationFactory.statusNotification(text)
        } else {
            notificationFactory.workingNotification(text)
        }
    }

    private fun updatePairingInput(
        transform: (PrivilegeAdbPairingInputState) -> PrivilegeAdbPairingInputState,
    ) {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return
        pairingInputState = transform(pairingInputState)
        showInputNotification()
    }

    private fun submitPairingCode(
        code: String,
        submittedViaRemoteInput: Boolean,
    ) {
        val ownerId = notificationOwnerId ?: return
        if (!ensureNotificationUiAvailable() || !code.isPrivilegeUiPairingCode()) return
        if (submittedViaRemoteInput) {
            lastRemoteInputSubmissionElapsedRealtime = SystemClock.elapsedRealtime()
        }
        val workingText = privilegeUiText(R.string.priv_ui_pairing_working_text)
        storeLatestStatus(
            ownerId = ownerId,
            text = workingText,
            acceptsPairingCode = false,
        )
        renderStatus(
            text = workingText,
            acceptsPairingCode = false,
        )
        if (notificationOwnerId == ownerId) {
            notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Submit(ownerId, code))
        }
    }

    private fun renderStatus(
        text: PrivilegeUiText,
        acceptsPairingCode: Boolean,
    ) {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return
        val resolvedText = text.asString(this)
        val notification = if (acceptsPairingCode) {
            notificationFactory.statusNotification(resolvedText)
        } else {
            notificationFactory.workingNotification(resolvedText)
        }
        startForegroundSafely(notification)
    }

    private fun showInputNotification(): Boolean {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return false
        return try {
            notifyInputSafely(notificationFactory.inputNotification(pairingInputState))
            true
        } catch (_: SecurityException) {
            handleNotificationUnavailable(
                PrivilegeAdbPairingNotificationUnavailableReason.NOTIFICATION_PERMISSION_REQUIRED,
            )
            false
        }
    }

    private fun ensureNotificationUiAvailable(): Boolean {
        if (notificationsAvailable(this)) return true
        handleNotificationUnavailable(
            PrivilegeAdbPairingNotificationUnavailableReason.NOTIFICATION_PERMISSION_REQUIRED,
        )
        return false
    }

    private fun handleNotificationUnavailable(
        reason: PrivilegeAdbPairingNotificationUnavailableReason,
    ) {
        val failureKind = reason.toPrivilegeUiFailureKind()
        notificationOwnerId?.let { ownerId ->
            notificationEventState.tryEmit(
                PrivilegeAdbPairingNotificationEvent.Unavailable(
                    ownerId = ownerId,
                    message = privilegeUiText(failureKind.messageResId).asString(this),
                    reason = reason,
                ),
            )
        }
        stopNotificationService()
    }

    private fun stopNotificationService() {
        val ownerId = notificationOwnerId
        notificationOwnerId = null
        cancelPendingStop()
        lastRemoteInputSubmissionElapsedRealtime = NO_REMOTE_INPUT_SUBMISSION
        if (latestOwnerId == ownerId) {
            latestOwnerId = null
            latestStatusText = null
            latestAcceptsPairingCode = true
        }
        cancelInputNotification()
        clearActiveService(ownerId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelStatusNotification()
        stopSelf()
    }

    private fun requestStop(ownerId: String) {
        if (notificationOwnerId != ownerId) return
        val graceMillis = remainingRemoteInputDismissGraceMillis()
        if (graceMillis <= 0L) {
            stopNotificationService()
            return
        }
        cancelInputNotification()
        pendingStopOwnerId = ownerId
        mainHandler.removeCallbacks(pendingStopRunnable)
        mainHandler.postDelayed(pendingStopRunnable, graceMillis)
    }

    private fun remainingRemoteInputDismissGraceMillis(): Long {
        val submittedAt = lastRemoteInputSubmissionElapsedRealtime
        if (submittedAt == NO_REMOTE_INPUT_SUBMISSION) return 0L
        val elapsed = SystemClock.elapsedRealtime() - submittedAt
        return (REMOTE_INPUT_DISMISS_GRACE_MILLIS - elapsed).coerceAtLeast(0L)
    }

    private fun cancelPendingStop() {
        pendingStopOwnerId = null
        mainHandler.removeCallbacks(pendingStopRunnable)
    }

    private fun cancelInputNotification() {
        notificationManager.cancel(PrivilegeAdbPairingIntentContract.INPUT_NOTIFICATION_ID)
    }

    private fun cancelStatusNotification() {
        notificationManager.cancel(PrivilegeAdbPairingIntentContract.NOTIFICATION_ID)
    }

    private fun startForegroundSafely(notification: Notification) {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return
        try {
            startForeground(PrivilegeAdbPairingIntentContract.NOTIFICATION_ID, notification)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to start pairing foreground service", throwable)
            handleNotificationUnavailable(
                PrivilegeAdbPairingNotificationUnavailableReason.FOREGROUND_SERVICE_FAILED,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyInputSafely(notification: Notification) {
        notificationManager.notify(PrivilegeAdbPairingIntentContract.INPUT_NOTIFICATION_ID, notification)
    }

    private fun clearActiveService(ownerId: String?) {
        if (activeService === this) {
            activeService = null
        }
        if (latestOwnerId == ownerId) {
            latestOwnerId = null
            latestStatusText = null
            latestAcceptsPairingCode = true
        }
    }

    private val notificationManager: NotificationManagerCompat
        get() = NotificationManagerCompat.from(this)

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
    public companion object {
        @Volatile
        private var activeService: PrivilegeAdbPairingService? = null

        @Volatile
        private var latestOwnerId: String? = null

        @Volatile
        private var latestStatusText: PrivilegeUiText? = null

        @Volatile
        private var latestAcceptsPairingCode: Boolean = true

        private val notificationEventState = MutableSharedFlow<PrivilegeAdbPairingNotificationEvent>(
            extraBufferCapacity = 16,
        )

        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
        public val notificationEvents: SharedFlow<PrivilegeAdbPairingNotificationEvent> =
            notificationEventState.asSharedFlow()

        public fun start(
            context: Context,
            ownerId: String,
            statusText: String,
        ): Boolean = startWithText(
            context = context,
            ownerId = ownerId,
            statusText = PrivilegeUiText.Literal(statusText),
        )

        internal fun startWithText(
            context: Context,
            ownerId: String,
            statusText: PrivilegeUiText,
        ): Boolean {
            require(ownerId.isNotBlank()) { "ownerId must not be blank" }
            if (!notificationsAvailable(context)) {
                activeService?.handleNotificationUnavailable(
                    PrivilegeAdbPairingNotificationUnavailableReason.NOTIFICATION_PERMISSION_REQUIRED,
                )
                return false
            }
            val previousOwnerId = latestOwnerId
            val previousStatusText = latestStatusText
            val previousAcceptsPairingCode = latestAcceptsPairingCode
            latestOwnerId = ownerId
            latestStatusText = statusText
            latestAcceptsPairingCode = true
            try {
                context.startForegroundService(
                    Intent(context, PrivilegeAdbPairingService::class.java)
                        .setAction(PrivilegeAdbPairingIntentContract.ACTION_START)
                        .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_OWNER_ID, ownerId),
                )
            } catch (throwable: Throwable) {
                if (latestOwnerId == ownerId) {
                    latestOwnerId = previousOwnerId
                    latestStatusText = previousStatusText
                    latestAcceptsPairingCode = previousAcceptsPairingCode
                }
                throw throwable
            }
            return true
        }

        public fun stop(context: Context, ownerId: String) {
            if (latestOwnerId != ownerId) return
            activeService?.let { service ->
                if (service.notificationOwnerId == ownerId) {
                    service.requestStop(ownerId)
                    return
                }
            }
            latestOwnerId = null
            latestStatusText = null
            latestAcceptsPairingCode = true
            context.stopService(Intent(context, PrivilegeAdbPairingService::class.java))
        }

        internal fun updateStatus(
            ownerId: String,
            text: PrivilegeUiText,
            acceptsPairingCode: Boolean = true,
        ) {
            if (!storeLatestStatus(ownerId, text, acceptsPairingCode)) return
            activeService
                ?.takeIf { it.notificationOwnerId == ownerId }
                ?.renderStatus(text, acceptsPairingCode)
        }

        private fun storeLatestStatus(
            ownerId: String,
            text: PrivilegeUiText,
            acceptsPairingCode: Boolean,
        ): Boolean {
            if (latestOwnerId != ownerId) return false
            latestStatusText = text
            latestAcceptsPairingCode = acceptsPairingCode
            return true
        }

        internal fun isRunning(ownerId: String): Boolean =
            activeService?.notificationOwnerId == ownerId

        internal fun isRequested(ownerId: String): Boolean =
            latestOwnerId == ownerId

        private fun notificationsAvailable(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            return manager.getNotificationChannel(PrivilegeAdbPairingIntentContract.NOTIFICATION_CHANNEL_ID)
                ?.importance != NotificationManager.IMPORTANCE_NONE
        }

        private const val TAG = "PrivKitPairing"
        private const val NO_REMOTE_INPUT_SUBMISSION: Long = -1L

        // SystemUI releases a sent RemoteInput's lifetime extension on a 200 ms timer. Keep the
        // acknowledgement alive beyond that window, with headroom for OEM SystemUI scheduling.
        private const val REMOTE_INPUT_DISMISS_GRACE_MILLIS: Long = 500L
    }
}
