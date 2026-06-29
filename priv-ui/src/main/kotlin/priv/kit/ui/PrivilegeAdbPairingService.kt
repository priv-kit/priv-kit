package priv.kit.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RestrictTo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import priv.kit.PrivilegeRuntime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public class PrivilegeAdbPairingService public constructor() : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchGeneration = AtomicInteger()
    private var activeTask: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        runningState.value = true
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = when (intent?.action) {
            ACTION_START -> startSearch(intent.requestedAdbDeviceName)
            ACTION_REPLY -> {
                val code = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(REMOTE_INPUT_PAIRING_CODE)
                    ?.toString()
                    .orEmpty()
                val port = intent.getIntExtra(EXTRA_PAIRING_PORT, -1)
                startPairing(
                    pairingCode = code,
                    port = port,
                    adbDeviceName = intent.requestedAdbDeviceName,
                )
            }
            ACTION_RETRY -> startSearch(intent.requestedAdbDeviceName)
            ACTION_STOP -> {
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

    override fun onDestroy() {
        searchGeneration.incrementAndGet()
        activeTask?.cancel(true)
        executor.shutdownNow()
        runningState.value = false
        deleteNotificationChannel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) {
            return
        }
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.priv_ui_pairing_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    private fun deleteNotificationChannel() {
        notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_ID)
    }

    private fun startSearch(adbDeviceName: String?): Notification {
        val generation = searchGeneration.incrementAndGet()
        activeTask?.cancel(true)
        sendPairingEvent(
            event = EVENT_SEARCHING,
            message = getString(R.string.priv_ui_checking_wireless_adb),
        )
        activeTask = executor.submit {
            val starter = PrivilegeRuntime.createAdbStarter(adbDeviceName = adbDeviceName)
            while (generation == searchGeneration.get()) {
                try {
                    val port = starter.discoverPairingPort(PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS)
                    val identityInfo = runCatching { starter.getIdentityInfo() }.getOrNull()
                    val message = getString(R.string.priv_ui_pairing_service_found_text)
                    sendPairingEvent(
                        event = EVENT_FOUND,
                        message = message,
                        port = port,
                        adbDeviceName = identityInfo?.identity?.deviceName,
                        fingerprint = identityInfo?.publicKeyFingerprint,
                    )
                    updateForegroundNotification(
                        inputNotification(
                            port = port,
                            adbDeviceName = adbDeviceName,
                            text = message,
                        ),
                    )
                    return@submit
                } catch (_: Throwable) {
                    if (generation != searchGeneration.get()) return@submit

                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        val message = getString(R.string.priv_ui_pairing_requires_android_11)
                        sendPairingEvent(event = EVENT_FAILED, message = message)
                        finishWithNotification(
                            notification = resultNotification(
                                title = getString(R.string.priv_ui_pairing_failed_title),
                                text = message,
                                success = false,
                                adbDeviceName = adbDeviceName,
                            ),
                        )
                        return@submit
                    }

                    val message = getString(R.string.priv_ui_pairing_search_attempt)
                    sendPairingEvent(event = EVENT_SEARCHING, message = message)
                    updateForegroundNotification(searchingNotification(text = message))
                    runCatching { Thread.sleep(PAIRING_DISCOVERY_RETRY_DELAY_MILLIS) }
                        .onFailure { return@submit }
                }
            }
        }
        return searchingNotification()
    }

    private fun startPairing(
        pairingCode: String,
        port: Int,
        adbDeviceName: String?,
    ): Notification {
        val code = pairingCode.toPairingCodeDigits()
        if (port !in 1..65535) {
            val message = getString(R.string.priv_ui_pairing_port_unavailable)
            sendPairingEvent(event = EVENT_FAILED, message = message)
            runningState.value = false
            stopSelf()
            return resultNotification(
                title = getString(R.string.priv_ui_pairing_failed_title),
                text = message,
                success = false,
                adbDeviceName = adbDeviceName,
            )
        }
        if (code.isBlank()) {
            val message = getString(R.string.priv_ui_pairing_code_required)
            sendPairingEvent(event = EVENT_FAILED, message = message, port = port)
            runningState.value = false
            stopSelf()
            return resultNotification(
                title = getString(R.string.priv_ui_pairing_failed_title),
                text = message,
                success = false,
                adbDeviceName = adbDeviceName,
            )
        }

        activeTask?.cancel(true)
        searchGeneration.incrementAndGet()
        sendPairingEvent(
            event = EVENT_PAIRING,
            message = getString(R.string.priv_ui_pairing_with_port),
            port = port,
        )
        activeTask = executor.submit {
            try {
                val result = PrivilegeRuntime.createAdbStarter(adbDeviceName = adbDeviceName)
                    .pair(port = port, pairingCode = code)
                val message = getString(R.string.priv_ui_pairing_success_text)
                sendPairingEvent(
                    event = EVENT_PAIRED,
                    message = message,
                    port = result.port,
                    adbDeviceName = result.identity.deviceName,
                    fingerprint = result.publicKeyFingerprint,
                )
                finishWithNotification(
                    notification = resultNotification(
                        title = getString(R.string.priv_ui_pairing_success_title),
                        text = message,
                        success = true,
                        adbDeviceName = adbDeviceName,
                    ),
                )
            } catch (throwable: Throwable) {
                val message = getString(R.string.priv_ui_pairing_failed_text, throwable.failureMessage())
                sendPairingEvent(event = EVENT_FAILED, message = message, port = port)
                finishWithNotification(
                    notification = resultNotification(
                        title = getString(R.string.priv_ui_pairing_failed_title),
                        text = message,
                        success = false,
                        adbDeviceName = adbDeviceName,
                    ),
                )
            }
        }
        return workingNotification()
    }

    private fun stopPairing(message: String) {
        searchGeneration.incrementAndGet()
        activeTask?.cancel(true)
        runningState.value = false
        sendPairingEvent(event = EVENT_STOPPED, message = message)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishWithNotification(notification: Notification) {
        runningState.value = false
        stopForeground(STOP_FOREGROUND_DETACH)
        notificationManager.notify(NOTIFICATION_ID, notification)
        stopSelf()
    }

    private fun searchingNotification(
        text: String = getString(R.string.priv_ui_pairing_search_text),
    ): Notification =
        baseNotification(
            title = getString(R.string.priv_ui_pairing_search_title),
            text = text,
            ongoing = true,
        )
            .addAction(stopAction())
            .buildPersistent()

    private fun inputNotification(
        port: Int,
        adbDeviceName: String?,
        text: String,
    ): Notification =
        baseNotification(
            title = getString(R.string.priv_ui_pairing_service_found_title),
            text = text,
            ongoing = true,
        )
            .addAction(replyAction(port, adbDeviceName))
            .buildPersistent()

    private fun workingNotification(): Notification =
        baseNotification(
            title = getString(R.string.priv_ui_pairing_working_title),
            text = getString(R.string.priv_ui_pairing_working_text),
            ongoing = true,
        ).buildPersistent()

    private fun resultNotification(
        title: String,
        text: String,
        success: Boolean,
        adbDeviceName: String?,
    ): Notification {
        val builder = baseNotification(
            title = title,
            text = text,
            ongoing = false,
        ).setAutoCancel(success)
        if (!success) {
            builder.addAction(retryAction(adbDeviceName))
        }
        return builder.build()
    }

    private fun baseNotification(
        title: String,
        text: String,
        ongoing: Boolean,
    ): Notification.Builder =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(notificationSmallIcon())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply {
                openAppPendingIntent()?.let(::setContentIntent)
            }

    private fun notificationSmallIcon(): Int =
        applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon

    private fun replyAction(port: Int, adbDeviceName: String?): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_PAIRING_CODE)
            .setLabel(getString(R.string.priv_ui_pairing_reply_label))
            .setAllowFreeFormInput(true)
            .build()
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_REPLY,
            serviceIntent(ACTION_REPLY, adbDeviceName).putExtra(EXTRA_PAIRING_PORT, port),
            mutablePendingIntentFlags(),
        )
        val actionBuilder = Notification.Action.Builder(
            null,
            getString(R.string.priv_ui_pairing_reply_action),
            pendingIntent,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            actionBuilder.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
        }
        return actionBuilder.build()
    }

    private fun retryAction(adbDeviceName: String?): Notification.Action {
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_RETRY,
            serviceIntent(ACTION_RETRY, adbDeviceName),
            immutablePendingIntentFlags(),
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.priv_ui_pairing_retry_action),
            pendingIntent,
        ).build()
    }

    private fun stopAction(): Notification.Action {
        val pendingIntent = PendingIntent.getForegroundService(
            this,
            REQUEST_STOP,
            serviceIntent(ACTION_STOP, adbDeviceName = null),
            immutablePendingIntentFlags(),
        )
        return Notification.Action.Builder(
            null,
            getString(R.string.priv_ui_pairing_stop_action),
            pendingIntent,
        ).build()
    }

    private fun openAppPendingIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            launchIntent,
            immutablePendingIntentFlags(),
        )
    }

    private fun serviceIntent(action: String, adbDeviceName: String?): Intent =
        Intent(this, PrivilegeAdbPairingService::class.java)
            .setAction(action)
            .apply {
                if (!adbDeviceName.isNullOrBlank()) {
                    putExtra(EXTRA_REQUESTED_ADB_DEVICE_NAME, adbDeviceName)
                }
            }

    private fun Notification.Builder.buildPersistent(): Notification =
        build().apply {
            flags = flags or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        }

    private fun updateForegroundNotification(notification: Notification) {
        mainHandler.post {
            startForegroundSafely(notification)
        }
    }

    private fun startForegroundSafely(notification: Notification) {
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (throwable: Throwable) {
            notificationManager.notify(NOTIFICATION_ID, notification)
            sendPairingEvent(
                event = EVENT_FAILED,
                message = getString(R.string.priv_ui_pairing_foreground_failed, throwable.failureMessage()),
            )
        }
    }

    private fun sendPairingEvent(
        event: String,
        message: String,
        port: Int? = null,
        adbDeviceName: String? = null,
        fingerprint: String? = null,
    ) {
        val intent = Intent(actionPairingEvent(this))
            .setPackage(packageName)
            .putExtra(EXTRA_EVENT, event)
            .putExtra(EXTRA_MESSAGE, message)
        if (port != null) intent.putExtra(EXTRA_PAIRING_PORT, port)
        if (adbDeviceName != null) intent.putExtra(EXTRA_ADB_DEVICE_NAME, adbDeviceName)
        if (fingerprint != null) intent.putExtra(EXTRA_ADB_KEY_FINGERPRINT, fingerprint)
        sendBroadcast(intent)
    }

    private val Intent.requestedAdbDeviceName: String?
        get() = getStringExtra(EXTRA_REQUESTED_ADB_DEVICE_NAME)

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun Throwable.failureMessage(): String =
        message ?: javaClass.simpleName

    internal companion object {
        internal const val EXTRA_EVENT: String = "event"
        internal const val EXTRA_MESSAGE: String = "message"
        internal const val EXTRA_PAIRING_PORT: String = "pairing_port"
        internal const val EXTRA_ADB_DEVICE_NAME: String = "adb_device_name"
        internal const val EXTRA_ADB_KEY_FINGERPRINT: String = "adb_key_fingerprint"

        internal const val EVENT_SEARCHING: String = "searching"
        internal const val EVENT_FOUND: String = "found"
        internal const val EVENT_PAIRING: String = "pairing"
        internal const val EVENT_PAIRED: String = "paired"
        internal const val EVENT_FAILED: String = "failed"
        internal const val EVENT_STOPPED: String = "stopped"

        private const val ACTION_START = "priv.kit.ui.action.START_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_REPLY = "priv.kit.ui.action.REPLY_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_RETRY = "priv.kit.ui.action.RETRY_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_STOP = "priv.kit.ui.action.STOP_ADB_PAIRING_NOTIFICATION"

        private const val EXTRA_REQUESTED_ADB_DEVICE_NAME = "requested_adb_device_name"
        private const val REMOTE_INPUT_PAIRING_CODE = "pairing_code"
        private const val NOTIFICATION_CHANNEL_ID = "priv_ui_adb_pairing"
        private const val NOTIFICATION_ID = 201
        private const val REQUEST_REPLY = 1
        private const val REQUEST_RETRY = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_OPEN_APP = 4
        private const val PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS = 6_000L
        private const val PAIRING_DISCOVERY_RETRY_DELAY_MILLIS = 500L

        private val runningState = MutableStateFlow(false)
        internal val running: StateFlow<Boolean> = runningState.asStateFlow()

        internal fun actionPairingEvent(context: Context): String =
            context.packageName + ".priv.kit.ui.action.ADB_PAIRING_EVENT"

        internal fun start(context: Context, adbDeviceName: String?) {
            val intent = Intent(context, PrivilegeAdbPairingService::class.java)
                .setAction(ACTION_START)
                .apply {
                    if (!adbDeviceName.isNullOrBlank()) {
                        putExtra(EXTRA_REQUESTED_ADB_DEVICE_NAME, adbDeviceName)
                    }
                }
            context.startForegroundService(intent)
        }

        internal fun stop(context: Context) {
            context.startService(
                Intent(context, PrivilegeAdbPairingService::class.java)
                    .setAction(ACTION_STOP),
            )
        }

        private fun mutablePendingIntentFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }

        private fun immutablePendingIntentFlags(): Int =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}

private fun String.toPairingCodeDigits(): String =
    filter(Char::isDigit).take(6)
