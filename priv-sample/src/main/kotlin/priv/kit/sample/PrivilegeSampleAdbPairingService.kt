package priv.kit.sample

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import priv.kit.PrivilegeRuntime
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

internal class PrivilegeSampleAdbPairingService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val searchGeneration = AtomicInteger()
    private var activeTask: Future<*>? = null

    override fun onCreate() {
        super.onCreate()
        runningState.value = true
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Wireless ADB pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
            },
        )
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
                stopPairing("Notification pairing stopped.")
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSearch(adbDeviceName: String?): Notification {
        val generation = searchGeneration.incrementAndGet()
        activeTask?.cancel(true)
        sendPairingEvent(
            event = EVENT_SEARCHING,
            message = "Searching for the Wireless debugging pairing service...",
        )
        activeTask = executor.submit {
            val starter = PrivilegeRuntime.createAdbStarter(
                adbDeviceName = adbDeviceName,
            )
            var attempt = 1
            while (generation == searchGeneration.get()) {
                try {
                    val port = starter.discoverPairingPort(PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS)
                    val identityInfo = runCatching { starter.getIdentityInfo() }.getOrNull()
                    val message = "Pairing service found on port $port. Reply to the notification with the pairing code."
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
                        val message = "Wireless ADB pairing requires Android 11 or above."
                        sendPairingEvent(event = EVENT_FAILED, message = message)
                        finishWithNotification(
                            notification = resultNotification(
                                title = "Wireless ADB pairing failed",
                                text = message,
                                success = false,
                                adbDeviceName = adbDeviceName,
                            ),
                        )
                        return@submit
                    }

                    val message = "Searching for the Wireless debugging pairing service... attempt $attempt"
                    sendPairingEvent(event = EVENT_SEARCHING, message = message)
                    updateForegroundNotification(searchingNotification(text = message))
                    attempt += 1
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
            val message = "Pairing port is not available. Start notification pairing again."
            sendPairingEvent(event = EVENT_FAILED, message = message)
            runningState.value = false
            stopSelf()
            return resultNotification(
                title = "Wireless ADB pairing failed",
                text = message,
                success = false,
                adbDeviceName = adbDeviceName,
            )
        }
        if (code.isBlank()) {
            val message = "Pairing code is required."
            sendPairingEvent(event = EVENT_FAILED, message = message, port = port)
            runningState.value = false
            stopSelf()
            return resultNotification(
                title = "Wireless ADB pairing failed",
                text = message,
                success = false,
                adbDeviceName = adbDeviceName,
            )
        }

        activeTask?.cancel(true)
        searchGeneration.incrementAndGet()
        sendPairingEvent(
            event = EVENT_PAIRING,
            message = "Pairing with Wireless debugging on port $port...",
            port = port,
        )
        activeTask = executor.submit {
            try {
                val result = PrivilegeRuntime.createAdbStarter(
                    adbDeviceName = adbDeviceName,
                )
                    .pair(port = port, pairingCode = code)
                val message = "Paired as ${result.identity.deviceName} on port ${result.port}."
                sendPairingEvent(
                    event = EVENT_PAIRED,
                    message = message,
                    port = result.port,
                    adbDeviceName = result.identity.deviceName,
                    fingerprint = result.publicKeyFingerprint,
                )
                finishWithNotification(
                    notification = resultNotification(
                        title = "Wireless ADB paired",
                        text = message,
                        success = true,
                        adbDeviceName = adbDeviceName,
                    ),
                )
            } catch (throwable: Throwable) {
                val message = "Wireless ADB pairing failed: ${throwable.failureMessage()}"
                sendPairingEvent(event = EVENT_FAILED, message = message, port = port)
                finishWithNotification(
                    notification = resultNotification(
                        title = "Wireless ADB pairing failed",
                        text = message,
                        success = false,
                        adbDeviceName = adbDeviceName,
                    ),
                )
            }
        }
        return workingNotification(port)
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
        text: String = "Open Wireless debugging and choose Pair device with pairing code.",
    ): Notification =
        baseNotification(
            title = "Searching for pairing service",
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
            title = "Pairing service found",
            text = text,
            ongoing = true,
        )
            .addAction(replyAction(port, adbDeviceName))
            .buildPersistent()

    private fun workingNotification(port: Int): Notification =
        baseNotification(
            title = "Wireless ADB pairing",
            text = "Pairing with port $port...",
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
            .setSmallIcon(R.drawable.ic_priv_sample_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openSamplePendingIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)

    private fun replyAction(port: Int, adbDeviceName: String?): Notification.Action {
        val remoteInput = RemoteInput.Builder(REMOTE_INPUT_PAIRING_CODE)
            .setLabel("Pairing code")
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
            "Enter Pairing Code",
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
            "Retry",
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
            "Stop",
            pendingIntent,
        ).build()
    }

    private fun openSamplePendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN_SAMPLE,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            immutablePendingIntentFlags(),
        )

    private fun serviceIntent(action: String, adbDeviceName: String?): Intent =
        Intent(this, PrivilegeSampleAdbPairingService::class.java)
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
                message = "Unable to keep notification pairing in the foreground: ${throwable.failureMessage()}",
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
        val intent = Intent(ACTION_PAIRING_EVENT)
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

    companion object {
        const val ACTION_PAIRING_EVENT = "priv.kit.sample.action.ADB_PAIRING_EVENT"
        const val EXTRA_EVENT = "event"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_PAIRING_PORT = "pairing_port"
        const val EXTRA_ADB_DEVICE_NAME = "adb_device_name"
        const val EXTRA_ADB_KEY_FINGERPRINT = "adb_key_fingerprint"

        const val EVENT_SEARCHING = "searching"
        const val EVENT_FOUND = "found"
        const val EVENT_PAIRING = "pairing"
        const val EVENT_PAIRED = "paired"
        const val EVENT_FAILED = "failed"
        const val EVENT_STOPPED = "stopped"

        private const val ACTION_START = "priv.kit.sample.action.START_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_REPLY = "priv.kit.sample.action.REPLY_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_RETRY = "priv.kit.sample.action.RETRY_ADB_PAIRING_NOTIFICATION"
        private const val ACTION_STOP = "priv.kit.sample.action.STOP_ADB_PAIRING_NOTIFICATION"

        private const val EXTRA_REQUESTED_ADB_DEVICE_NAME = "requested_adb_device_name"
        private const val REMOTE_INPUT_PAIRING_CODE = "pairing_code"
        private const val NOTIFICATION_CHANNEL_ID = "priv_sample_adb_pairing"
        private const val NOTIFICATION_ID = 201
        private const val REQUEST_REPLY = 1
        private const val REQUEST_RETRY = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_OPEN_SAMPLE = 4
        private const val PAIRING_DISCOVERY_ATTEMPT_TIMEOUT_MILLIS = 6_000L
        private const val PAIRING_DISCOVERY_RETRY_DELAY_MILLIS = 500L

        private val runningState = MutableStateFlow(false)
        val running: StateFlow<Boolean> = runningState.asStateFlow()

        fun start(context: Context, adbDeviceName: String?) {
            val intent = Intent(context, PrivilegeSampleAdbPairingService::class.java)
                .setAction(ACTION_START)
                .apply {
                    if (!adbDeviceName.isNullOrBlank()) {
                        putExtra(EXTRA_REQUESTED_ADB_DEVICE_NAME, adbDeviceName)
                    }
                }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, PrivilegeSampleAdbPairingService::class.java)
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
