package priv.kit.core.internal.server

import androidx.annotation.Keep
import androidx.annotation.RestrictTo
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin
import java.io.File
import kotlin.system.exitProcess

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public object PrivilegeServerMain {
    private val lock = Any()
    private val ownerProcessSignal = PrivilegeOwnerProcessSignal()
    private var ownerBinder: IBinder? = null
    private var activeConfig: PrivilegeServerConfig? = null
    private var activeServerBinder: PrivilegeServerBinder? = null
    private var ownerProcessObserver: PrivilegeOwnerProcessObserver? = null
    private var ownerProcessObserverRegistered = false
    private var reconnectGeneration = 0

    private val ownerDeathRecipient = IBinder.DeathRecipient {
        val state = synchronized(lock) {
            ownerBinder = null
            val config = activeConfig
            val serverBinder = activeServerBinder
            if (config == null || serverBinder == null) {
                null
            } else {
                OwnerReconnectState(
                    config = config,
                    serverBinder = serverBinder,
                    startAfterSignalSequence = ownerProcessSignal.acknowledgedSnapshot(),
                    ownerProcessObserverRegistered = ownerProcessObserverRegistered,
                )
            }
        }
        if (state == null) {
            Log.i(TAG, "Owner process died before server state was ready; exiting Privileged Server")
            exitServer(0)
        }
        state.serverBinder.destroyUserServicesOnOwnerDeath()
        scheduleOwnerReconnect(state, "Owner process died")
    }

    @Keep
    @JvmStatic
    public fun main(args: Array<String>) {
        try {
            Log.i(TAG, "Privileged Server main entered args=${args.toDiagnosticString()}")
            prepareMainLooper()
            val config = PrivilegeServerArguments.parse(
                args = args,
                classpath = System.getenv("CLASSPATH").orEmpty(),
                launchCorrelationId =
                    System.getenv(PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID)
                        ?.takeIf { it.isNotBlank() },
                uid = android.os.Process.myUid(),
            )
            val providerAuthority = PrivilegeHandshakeContract.providerAuthority(config.packageName)
            Log.i(
                TAG,
                "Config parsed package=${config.packageName}, provider=$providerAuthority, " +
                    "userId=${config.userId}, " +
                    "protocol=${config.protocolVersion}",
            )
            val binder = PrivilegeServerBinder(
                config = config,
                onShutdown = ::closeOwnerProcessObserver,
            )
            Log.i(TAG, "Sending handshake uid=${android.os.Process.myUid()}, pid=${android.os.Process.myPid()}")
            val handshakeResult = PrivilegeServerHandshakeSender.send(
                config = config,
                serverBinder = binder,
                origin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
            )
            Log.i(TAG, "Handshake result accepted=${handshakeResult.accepted}")
            if (!handshakeResult.accepted) {
                if (handshakeResult.replacementStarted) {
                    Log.i(TAG, "Replacement Privileged Server started; exiting stale server")
                    exitServer(0)
                }
                System.err.println("Privileged Server handshake was rejected")
                exitServer(2)
            }
            val ownerConfig = handshakeResult.ownerConfig
            Log.i(
                TAG,
                "Owner config received followDeathDelayMillis=${ownerConfig.followDeathDelayMillis}, " +
                    "activeReconnectOnOwnerDeath=${ownerConfig.activeReconnectOnOwnerDeath}",
            )
            synchronized(lock) {
                activeConfig = ownerConfig
                activeServerBinder = binder
            }
            registerOwnerProcessObserver(config)
            watchOwner(
                binder = handshakeResult.ownerBinder,
                config = ownerConfig,
                serverBinder = binder,
            )
            keepAlive()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Privileged Server failed before keepAlive", throwable)
            throwable.printStackTrace(System.err)
            exitServer(1)
        }
    }

    private fun registerOwnerProcessObserver(config: PrivilegeServerConfig) {
        val observer = PrivilegeOwnerProcessObserver(
            packageName = config.packageName,
            userId = config.userId,
            onOwnerProcessStarted = ::handleOwnerProcessStarted,
        )
        val registered = observer.register()
        synchronized(lock) {
            ownerProcessObserver = observer
            ownerProcessObserverRegistered = registered
        }
    }

    private fun handleOwnerProcessStarted() {
        synchronized(lock) {
            val binder = ownerBinder
            val ownerIsAlive = runCatching {
                binder?.isBinderAlive == true && binder.pingBinder()
            }.getOrDefault(false)
            ownerProcessSignal.signal(ownerIsAlive = ownerIsAlive)
        }
    }

    private fun watchOwner(
        binder: IBinder?,
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
    ) {
        if (binder == null) {
            Log.w(TAG, "Handshake did not return an owner Binder; server will not follow app process death")
            return
        }
        try {
            synchronized(lock) {
                ownerBinder = binder
            }
            binder.linkToDeath(ownerDeathRecipient, 0)
            val ownerStillLinked = synchronized(lock) {
                if (ownerBinder === binder) {
                    ownerProcessSignal.acknowledgeCurrent()
                    true
                } else {
                    false
                }
            }
            if (ownerStillLinked) {
                Log.i(TAG, "Linked Privileged Server lifetime to owner process")
            }
        } catch (_: RemoteException) {
            val reconnectState = synchronized(lock) {
                if (ownerBinder !== binder) {
                    null
                } else {
                    ownerBinder = null
                    OwnerReconnectState(
                        config = config,
                        serverBinder = serverBinder,
                        startAfterSignalSequence = ownerProcessSignal.acknowledgedSnapshot(),
                        ownerProcessObserverRegistered = ownerProcessObserverRegistered,
                    )
                }
            }
            if (reconnectState != null) {
                scheduleOwnerReconnect(
                    state = reconnectState,
                    reason = "Owner process died before death recipient was linked",
                )
            }
        }
    }

    private fun scheduleOwnerReconnect(
        state: OwnerReconnectState,
        reason: String,
    ) {
        val config = state.config
        val delayMillis = config.followDeathDelayMillis
        if (delayMillis <= 0L) {
            Log.i(TAG, "$reason; follow death delay is zero, exiting Privileged Server")
            exitServer(0)
        }

        val generation = synchronized(lock) {
            reconnectGeneration += 1
            reconnectGeneration
        }
        Log.i(
            TAG,
            "$reason; waiting ${delayMillis}ms for owner reconnect, " +
                "activeReconnect=${config.activeReconnectOnOwnerDeath}",
        )
        Thread {
            if (config.activeReconnectOnOwnerDeath) {
                reconnectOwnerUntilDeadline(
                    config = config,
                    serverBinder = state.serverBinder,
                    generation = generation,
                    delayMillis = delayMillis,
                )
            } else if (state.ownerProcessObserverRegistered) {
                reconnectOwnerWhenAppStarts(
                    config = config,
                    serverBinder = state.serverBinder,
                    generation = generation,
                    delayMillis = delayMillis,
                    startAfterSignalSequence = state.startAfterSignalSequence,
                )
            } else {
                reconnectOwnerWithProcessPollingFallback(
                    config = config,
                    serverBinder = state.serverBinder,
                    generation = generation,
                    delayMillis = delayMillis,
                )
            }
        }.apply {
            name = "priv-kit-owner-reconnect"
            isDaemon = true
            start()
        }
    }

    private fun reconnectOwnerUntilDeadline(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        delayMillis: Long,
    ) {
        val deadlineMillis = SystemClock.elapsedRealtime() + delayMillis
        var attempt = 0
        while (isCurrentReconnect(generation)) {
            val remainingMillis = deadlineMillis - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                break
            }

            attempt += 1
            if (attemptOwnerReconnect(config, serverBinder, generation, attempt)) {
                return
            }

            val sleepMillis = minOf(
                OWNER_RECONNECT_RETRY_DELAY_MILLIS,
                deadlineMillis - SystemClock.elapsedRealtime(),
            )
            if (sleepMillis > 0L) {
                runCatching {
                    Thread.sleep(sleepMillis)
                }
            }
        }

        if (isCurrentReconnect(generation)) {
            Log.i(TAG, "Owner did not reconnect within ${delayMillis}ms; exiting Privileged Server")
            exitServer(0)
        }
    }

    private fun reconnectOwnerWhenAppStarts(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        delayMillis: Long,
        startAfterSignalSequence: Long,
    ) {
        val deadlineMillis = SystemClock.elapsedRealtime() + delayMillis
        var signalSequence = startAfterSignalSequence
        var attempt = 0
        while (isCurrentReconnect(generation)) {
            val remainingMillis = deadlineMillis - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                break
            }

            val nextSignalSequence = try {
                ownerProcessSignal.awaitNext(
                    afterSequence = signalSequence,
                    timeoutMillis = remainingMillis,
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } ?: break
            signalSequence = nextSignalSequence
            if (!isCurrentReconnect(generation)) {
                return
            }

            attempt += 1
            Log.i(TAG, "Owner app process start observed; attempting passive reconnect")
            if (attemptOwnerReconnect(config, serverBinder, generation, attempt)) {
                return
            }
        }

        if (isCurrentReconnect(generation)) {
            Log.i(TAG, "Owner did not restart within ${delayMillis}ms; exiting Privileged Server")
            exitServer(0)
        }
    }

    private fun reconnectOwnerWithProcessPollingFallback(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        delayMillis: Long,
    ) {
        val deadlineMillis = SystemClock.elapsedRealtime() + delayMillis
        var attempt = 0
        var appWasMissing = false
        while (isCurrentReconnect(generation)) {
            val remainingMillis = deadlineMillis - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                break
            }

            if (isOwnerAppProcessRunning(config.packageName)) {
                if (appWasMissing || attempt == 0) {
                    Log.i(TAG, "Owner app process is running; attempting passive reconnect")
                }
                attempt += 1
                if (attemptOwnerReconnect(config, serverBinder, generation, attempt)) {
                    return
                }
                appWasMissing = false
            } else {
                if (!appWasMissing) {
                    Log.i(TAG, "Owner app process is not running; waiting for user restart")
                    appWasMissing = true
                }
            }

            val sleepMillis = minOf(
                OWNER_RECONNECT_RETRY_DELAY_MILLIS,
                deadlineMillis - SystemClock.elapsedRealtime(),
            )
            if (sleepMillis > 0L) {
                runCatching {
                    Thread.sleep(sleepMillis)
                }
            }
        }

        if (isCurrentReconnect(generation)) {
            Log.i(TAG, "Owner did not restart within ${delayMillis}ms; exiting Privileged Server")
            exitServer(0)
        }
    }

    private fun attemptOwnerReconnect(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        attempt: Int,
    ): Boolean {
        val result = runCatching {
            Log.i(TAG, "Owner reconnect attempt $attempt")
            PrivilegeServerHandshakeSender.send(
                config = config,
                serverBinder = serverBinder,
                origin = PrivilegeServerHandshakeOrigin.OWNER_RECONNECT,
            )
        }.getOrElse { throwable ->
            Log.w(TAG, "Owner reconnect attempt $attempt failed", throwable)
            null
        }

        if (result?.accepted == true) {
            val newOwnerBinder = result.ownerBinder
            if (newOwnerBinder != null && relinkOwner(newOwnerBinder, generation, result.ownerConfig)) {
                Log.i(
                    TAG,
                    "Owner reconnect accepted on attempt $attempt, " +
                        "followDeathDelayMillis=${result.ownerConfig.followDeathDelayMillis}, " +
                        "activeReconnectOnOwnerDeath=${result.ownerConfig.activeReconnectOnOwnerDeath}",
                )
                return true
            }
            Log.w(TAG, "Owner reconnect attempt $attempt did not return a live owner Binder")
        }
        if (result?.replacementStarted == true) {
            Log.i(TAG, "Replacement Privileged Server started; exiting stale server")
            exitServer(0)
        }
        return false
    }

    private fun relinkOwner(
        binder: IBinder,
        generation: Int,
        ownerConfig: PrivilegeServerConfig,
    ): Boolean {
        synchronized(lock) {
            if (generation != reconnectGeneration) {
                return false
            }
            ownerBinder = binder
            activeConfig = ownerConfig
        }
        return try {
            binder.linkToDeath(ownerDeathRecipient, 0)
            synchronized(lock) {
                if (generation != reconnectGeneration || ownerBinder !== binder) {
                    runCatching {
                        binder.unlinkToDeath(ownerDeathRecipient, 0)
                    }
                    return false
                }
                ownerProcessSignal.acknowledgeCurrent()
            }
            true
        } catch (_: RemoteException) {
            synchronized(lock) {
                if (ownerBinder === binder) {
                    ownerBinder = null
                }
            }
            false
        }
    }

    private fun isCurrentReconnect(generation: Int): Boolean =
        synchronized(lock) {
            generation == reconnectGeneration && ownerBinder == null
        }

    private fun isOwnerAppProcessRunning(packageName: String): Boolean =
        File("/proc").listFiles()?.any { processDirectory ->
            val pid = processDirectory.name.toIntOrNull() ?: return@any false
            if (pid == android.os.Process.myPid()) {
                return@any false
            }
            readProcessName(pid) == packageName
        } == true

    private fun readProcessName(pid: Int): String? =
        runCatching {
            val bytes = File("/proc/$pid/cmdline").readBytes()
            val length = bytes.indexOf(0.toByte()).let { index ->
                if (index >= 0) index else bytes.size
            }
            if (length <= 0) {
                null
            } else {
                String(bytes, 0, length, Charsets.UTF_8)
            }
        }.getOrNull()

    private fun closeOwnerProcessObserver() {
        val observer = synchronized(lock) {
            ownerProcessObserverRegistered = false
            ownerProcessObserver.also {
                ownerProcessObserver = null
            }
        }
        observer?.close()
    }

    private fun exitServer(status: Int): Nothing {
        closeOwnerProcessObserver()
        exitProcess(status)
    }

    @Suppress("DEPRECATION")
    private fun prepareMainLooper() {
        if (Looper.myLooper() == null) {
            Looper.prepareMainLooper()
        }
    }

    private fun keepAlive() {
        Log.i(TAG, "Privileged Server entering Looper")
        Looper.loop()
    }

    private fun Array<String>.toDiagnosticString(): String =
        joinToString(prefix = "[", postfix = "]") { arg ->
            if (arg.length > 16 && arg.any { it.isLetterOrDigit() }) {
                arg.take(16) + "..."
            } else {
                arg
            }
        }

    private data class OwnerReconnectState(
        val config: PrivilegeServerConfig,
        val serverBinder: PrivilegeServerBinder,
        val startAfterSignalSequence: Long,
        val ownerProcessObserverRegistered: Boolean,
    )

    private const val TAG = "PrivKitServer"
    private const val OWNER_RECONNECT_RETRY_DELAY_MILLIS = 1_000L
}
