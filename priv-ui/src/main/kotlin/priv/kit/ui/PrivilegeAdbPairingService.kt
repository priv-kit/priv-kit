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
    private lateinit var notificationFactory: PrivilegeAdbPairingNotificationFactory

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
            PrivilegeAdbPairingIntentContract.ACTION_REPLY -> submitPairingCode(
                RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(PrivilegeAdbPairingIntentContract.REMOTE_INPUT_PAIRING_CODE)
                    ?.toString()
                    ?.trim()
                    .orEmpty(),
            )
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
            PrivilegeAdbPairingIntentContract.ACTION_INPUT_SUBMIT -> submitPairingCode(pairingInputState.code)
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
        cancelInputNotification()
        cancelStatusNotification()
        clearActiveService(detachedOwnerId)
        detachedOwnerId?.let { ownerId ->
            notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Detached(ownerId))
        }
        super.onDestroy()
    }

    private fun attachOwner(ownerId: String) {
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
        return notificationFactory.statusNotification(
            text = (latestStatusText ?: privilegeUiText(R.string.priv_ui_pairing_search_text))
                .asString(this),
        )
    }

    private fun updatePairingInput(
        transform: (PrivilegeAdbPairingInputState) -> PrivilegeAdbPairingInputState,
    ) {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return
        pairingInputState = transform(pairingInputState)
        showInputNotification()
    }

    private fun submitPairingCode(code: String): Notification? {
        val ownerId = notificationOwnerId ?: return null
        if (!ensureNotificationUiAvailable() || !code.isPrivilegeUiPairingCode()) return null
        notificationEventState.tryEmit(PrivilegeAdbPairingNotificationEvent.Submit(ownerId, code))
        return notificationFactory.workingNotification()
    }

    private fun renderStatus(text: PrivilegeUiText) {
        if (notificationOwnerId == null || !ensureNotificationUiAvailable()) return
        startForegroundSafely(notificationFactory.statusNotification(text = text.asString(this)))
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
        if (latestOwnerId == ownerId) {
            latestOwnerId = null
            latestStatusText = null
        }
        cancelInputNotification()
        clearActiveService(ownerId)
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelStatusNotification()
        stopSelf()
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
            latestOwnerId = ownerId
            latestStatusText = statusText
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
                }
                throw throwable
            }
            return true
        }

        public fun stop(context: Context, ownerId: String) {
            if (latestOwnerId != ownerId) return
            latestOwnerId = null
            latestStatusText = null
            activeService?.let { service ->
                if (service.notificationOwnerId == ownerId) {
                    service.stopNotificationService()
                    return
                }
            }
            context.stopService(Intent(context, PrivilegeAdbPairingService::class.java))
        }

        internal fun updateStatus(ownerId: String, text: PrivilegeUiText) {
            if (latestOwnerId != ownerId) return
            latestStatusText = text
            activeService?.takeIf { it.notificationOwnerId == ownerId }?.renderStatus(text)
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
    }
}
