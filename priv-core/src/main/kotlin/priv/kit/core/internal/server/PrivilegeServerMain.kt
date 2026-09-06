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
import priv.kit.core.internal.core.preparePrivilegeMainLooper
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment
import java.io.File
import kotlin.system.exitProcess

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
public object PrivilegeServerMain {
    private val lock = Any()
    private val ownerProcessSignal = PrivilegeOwnerProcessSignal()
    private val ownerCrashLoopGuard = PrivilegeOwnerCrashLoopGuard(
        windowMillis = OWNER_CRASH_LOOP_WINDOW_MILLIS,
        deathThreshold = OWNER_CRASH_LOOP_THRESHOLD,
    )
    private val ownerRestartPlanner = PrivilegeOwnerRestartPlanner(
        armingWindowMillis = OWNER_RESTART_ARM_WINDOW_MILLIS,
        elapsedRealtime = SystemClock::elapsedRealtime,
    )
    private var ownerBinder: IBinder? = null
    private var activeConfig: PrivilegeServerConfig? = null
    private var pendingRuntimeConfig: RuntimeConfig? = null
    private var activeServerBinder: PrivilegeServerBinder? = null
    private var ownerProcessObserver: PrivilegeOwnerProcessObserver? = null
    private var ownerProcessObserverRegistered = false
    private var reconnectGeneration = 0

    private val ownerDeathRecipient = IBinder.DeathRecipient {
        val state = synchronized(lock) {
            val deadOwnerBinder = ownerBinder
            ownerBinder = null
            val config = activeConfig
            val serverBinder = activeServerBinder
            if (deadOwnerBinder == null || config == null || serverBinder == null) {
                null
            } else {
                createOwnerReconnectStateLocked(
                    config = config,
                    serverBinder = serverBinder,
                    deadOwnerBinder = deadOwnerBinder,
                )
            }
        }
        if (state == null) {
            Log.i(TAG, "Owner process died before server state was ready; exiting Privileged Server")
            exitServer(0)
        }
        state.serverBinder.releaseOwnerResourcesOnDeath()
        scheduleOwnerReconnect(state, "Owner process died")
    }

    @Keep
    @JvmStatic
    public fun main(args: Array<String>) {
        try {
            PrivilegeUserServiceEnvironment.markServerProcess()
            Log.i(TAG, "Privileged Server main entered args=${args.toDiagnosticString()}")
            preparePrivilegeMainLooper()
            val config = PrivilegeServerArguments.parse(
                args = args,
                classpath = System.getenv("CLASSPATH").orEmpty(),
                launchCorrelationId =
                    System.getenv(PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID)
                        ?.takeIf { it.isNotBlank() },
                ownerUserId =
                    System.getenv(PrivilegeHandshakeContract.ENV_OWNER_USER_ID),
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
                onRuntimeConfigChanged = ::updateRuntimeConfig,
                onOwnerRestartPrepared = ::prepareOwnerRestart,
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
            val ownerConfig = synchronized(lock) {
                val initialConfig = handshakeResult.ownerConfig
                val runtimeConfig = pendingRuntimeConfig
                pendingRuntimeConfig = null
                val effectiveConfig = if (runtimeConfig == null) {
                    initialConfig
                } else {
                    initialConfig.copy(
                        followDeathDelayMillis = runtimeConfig.followDeathDelayMillis,
                        activeReconnectOnOwnerDeath = runtimeConfig.activeReconnectOnOwnerDeath,
                    )
                }
                activeConfig = effectiveConfig
                activeServerBinder = binder
                effectiveConfig
            }
            Log.i(
                TAG,
                "Owner config received followDeathDelayMillis=${ownerConfig.followDeathDelayMillis}, " +
                    "activeReconnectOnOwnerDeath=${ownerConfig.activeReconnectOnOwnerDeath}",
            )
            registerOwnerProcessObserver(config)
            watchOwner(
                binder = handshakeResult.ownerBinder,
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

    private fun updateRuntimeConfig(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        val updated = synchronized(lock) {
            val runtimeConfig = RuntimeConfig(
                followDeathDelayMillis = followDeathDelayMillis,
                activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
            )
            val current = activeConfig
            if (current == null) {
                pendingRuntimeConfig = runtimeConfig
                null
            } else {
                if (
                    !current.activeReconnectOnOwnerDeath &&
                    activeReconnectOnOwnerDeath &&
                    ownerBinder != null
                ) {
                    ownerCrashLoopGuard.onOwnerLinked(SystemClock.elapsedRealtime())
                }
                current.copy(
                    followDeathDelayMillis = followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
                ).also { activeConfig = it }
            }
        }
        if (updated != null) {
            Log.i(
                TAG,
                "Runtime config updated followDeathDelayMillis=${updated.followDeathDelayMillis}, " +
                    "activeReconnectOnOwnerDeath=${updated.activeReconnectOnOwnerDeath}",
            )
        }
    }

    private fun prepareOwnerRestart(
        passiveReconnectTimeoutMillis: Long,
        ownerPid: Int,
    ) {
        synchronized(lock) {
            ownerRestartPlanner.prepare(
                ownerBinder = ownerBinder,
                ownerPid = ownerPid,
                passiveReconnectTimeoutMillis = passiveReconnectTimeoutMillis,
            )
        }
        Log.i(
            TAG,
            "Owner restart prepared ownerPid=$ownerPid, " +
                "passiveReconnectTimeoutMillis=$passiveReconnectTimeoutMillis",
        )
    }

    private fun watchOwner(
        binder: IBinder?,
        serverBinder: PrivilegeServerBinder,
    ) {
        if (binder == null) {
            Log.w(TAG, "Handshake did not return an owner Binder; server will not follow app process death")
            return
        }
        try {
            synchronized(lock) {
                ownerBinder = binder
                ownerRestartPlanner.bindOwner(binder)
                val currentConfig = checkNotNull(activeConfig) {
                    "Owner config must be initialized before linking its Binder"
                }
                if (currentConfig.activeReconnectOnOwnerDeath) {
                    ownerCrashLoopGuard.onOwnerLinked(SystemClock.elapsedRealtime())
                }
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
                    val reconnectState = createOwnerReconnectStateLocked(
                        config = checkNotNull(activeConfig),
                        serverBinder = serverBinder,
                        deadOwnerBinder = binder,
                    )
                    ownerBinder = null
                    reconnectState
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

        val reconnectStartedAtMillis = SystemClock.elapsedRealtime()
        val generation = synchronized(lock) {
            reconnectGeneration += 1
            reconnectGeneration
        }
        logOwnerCrashLoopDecision(state.crashLoopDecision)
        val activeReconnect =
            config.activeReconnectOnOwnerDeath && !state.crashLoopDecision.circuitOpen
        val reconnectPhases = planOwnerReconnectPhases(
            startedAtMillis = reconnectStartedAtMillis,
            followDeathDelayMillis = delayMillis,
            activeReconnect = activeReconnect,
            plannedPassiveReconnectTimeoutMillis =
                state.plannedOwnerRestart?.passiveReconnectTimeoutMillis,
        )
        Log.i(
            TAG,
            "$reason; waiting ${delayMillis}ms for owner reconnect, " +
                "activeReconnect=$activeReconnect, " +
                "plannedRestart=${state.plannedOwnerRestart != null}",
        )
        Thread {
            reconnectPhases.forEachIndexed { index, phase ->
                if (!isCurrentReconnect(generation) || Thread.currentThread().isInterrupted) {
                    return@Thread
                }
                if (index > 0 && phase.mode == PrivilegeOwnerReconnectMode.ACTIVE) {
                    Log.i(TAG, "Planned owner restart grace expired; resuming active reconnect")
                }
                when (phase.mode) {
                    PrivilegeOwnerReconnectMode.PASSIVE -> reconnectOwnerPassivelyUntil(
                        config = config,
                        serverBinder = state.serverBinder,
                        generation = generation,
                        deadlineMillis = phase.deadlineMillis,
                        startAfterSignalSequence = state.startAfterSignalSequence,
                        ownerProcessObserverRegistered = state.ownerProcessObserverRegistered,
                        excludedOwnerPid = state.plannedOwnerRestart?.ownerPid,
                    )
                    PrivilegeOwnerReconnectMode.ACTIVE -> reconnectOwnerActivelyUntil(
                        config = config,
                        serverBinder = state.serverBinder,
                        generation = generation,
                        deadlineMillis = phase.deadlineMillis,
                    )
                }
            }

            if (isCurrentReconnect(generation) && !Thread.currentThread().isInterrupted) {
                Log.i(TAG, "Owner did not reconnect within ${delayMillis}ms; exiting Privileged Server")
                exitServer(0)
            }
        }.apply {
            name = "priv-kit-owner-reconnect"
            isDaemon = true
            start()
        }
    }

    private fun reconnectOwnerActivelyUntil(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        deadlineMillis: Long,
    ) {
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
            if (!isCurrentReconnect(generation)) {
                return
            }

            sleepUntilNextReconnectAttempt(deadlineMillis)
        }
    }

    private fun reconnectOwnerPassivelyUntil(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        deadlineMillis: Long,
        startAfterSignalSequence: Long,
        ownerProcessObserverRegistered: Boolean,
        excludedOwnerPid: Int?,
    ) {
        if (ownerProcessObserverRegistered) {
            reconnectOwnerWhenAppStartsUntil(
                config = config,
                serverBinder = serverBinder,
                generation = generation,
                deadlineMillis = deadlineMillis,
                startAfterSignalSequence = startAfterSignalSequence,
            )
        } else {
            reconnectOwnerWithProcessPollingUntil(
                config = config,
                serverBinder = serverBinder,
                generation = generation,
                deadlineMillis = deadlineMillis,
                excludedOwnerPid = excludedOwnerPid,
            )
        }
    }

    private fun reconnectOwnerWhenAppStartsUntil(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        deadlineMillis: Long,
        startAfterSignalSequence: Long,
    ) {
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
    }

    private fun reconnectOwnerWithProcessPollingUntil(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        generation: Int,
        deadlineMillis: Long,
        excludedOwnerPid: Int?,
    ) {
        var attempt = 0
        var appWasMissing = false
        while (isCurrentReconnect(generation)) {
            val remainingMillis = deadlineMillis - SystemClock.elapsedRealtime()
            if (remainingMillis <= 0L) {
                break
            }

            if (isOwnerAppProcessRunning(config.packageName, excludedOwnerPid)) {
                if (appWasMissing || attempt == 0) {
                    Log.i(TAG, "Owner app process is running; attempting passive reconnect")
                }
                attempt += 1
                if (attemptOwnerReconnect(config, serverBinder, generation, attempt)) {
                    return
                }
                if (!isCurrentReconnect(generation)) {
                    return
                }
                appWasMissing = false
            } else {
                if (!appWasMissing) {
                    Log.i(TAG, "Owner app process is not running; waiting for user restart")
                    appWasMissing = true
                }
            }

            sleepUntilNextReconnectAttempt(deadlineMillis)
        }
    }

    private fun sleepUntilNextReconnectAttempt(deadlineMillis: Long) {
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
            if (
                newOwnerBinder != null && relinkOwner(
                    binder = newOwnerBinder,
                    generation = generation,
                    serverBinder = serverBinder,
                )
            ) {
                val currentConfig = synchronized(lock) {
                    checkNotNull(activeConfig)
                }
                Log.i(
                    TAG,
                    "Owner reconnect accepted on attempt $attempt, " +
                        "followDeathDelayMillis=${currentConfig.followDeathDelayMillis}, " +
                        "activeReconnectOnOwnerDeath=${currentConfig.activeReconnectOnOwnerDeath}",
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
        serverBinder: PrivilegeServerBinder,
    ): Boolean {
        synchronized(lock) {
            if (generation != reconnectGeneration) {
                return false
            }
            ownerBinder = binder
            ownerRestartPlanner.bindOwner(binder)
            val currentConfig = checkNotNull(activeConfig) {
                "Owner config must remain initialized during reconnect"
            }
            if (currentConfig.activeReconnectOnOwnerDeath) {
                ownerCrashLoopGuard.onOwnerLinked(SystemClock.elapsedRealtime())
            }
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
            val reconnectState = synchronized(lock) {
                if (ownerBinder === binder) {
                    val state = createOwnerReconnectStateLocked(
                        config = checkNotNull(activeConfig),
                        serverBinder = serverBinder,
                        deadOwnerBinder = binder,
                    )
                    ownerBinder = null
                    state
                } else {
                    null
                }
            }
            if (reconnectState != null) {
                scheduleOwnerReconnect(
                    state = reconnectState,
                    reason = "Owner process died before death recipient was relinked",
                )
            }
            false
        }
    }

    private fun createOwnerReconnectStateLocked(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        deadOwnerBinder: IBinder,
    ): OwnerReconnectState {
        val nowMillis = SystemClock.elapsedRealtime()
        val plannedOwnerRestart =
            ownerRestartPlanner.consume(deadOwnerBinder)
        val crashLoopDecision = when {
            plannedOwnerRestart != null ->
                ownerCrashLoopGuard.onPlannedOwnerDeath(nowMillis)
            config.activeReconnectOnOwnerDeath -> ownerCrashLoopGuard.onOwnerDied(nowMillis)
            else -> PrivilegeOwnerCrashLoopDecision.CLOSED
        }
        return OwnerReconnectState(
            config = config,
            serverBinder = serverBinder,
            startAfterSignalSequence = if (
                plannedOwnerRestart != null || crashLoopDecision.circuitOpen
            ) {
                ownerProcessSignal.snapshot()
            } else {
                ownerProcessSignal.acknowledgedSnapshot()
            },
            ownerProcessObserverRegistered = ownerProcessObserverRegistered,
            crashLoopDecision = crashLoopDecision,
            plannedOwnerRestart = plannedOwnerRestart,
        )
    }

    private fun logOwnerCrashLoopDecision(decision: PrivilegeOwnerCrashLoopDecision) {
        when {
            decision.circuitOpened -> Log.w(
                TAG,
                "Owner crash-loop circuit opened after $OWNER_CRASH_LOOP_THRESHOLD deaths " +
                    "within ${OWNER_CRASH_LOOP_WINDOW_MILLIS}ms; active reconnect disabled",
            )
            decision.circuitReset -> Log.i(
                TAG,
                "Owner crash-loop circuit reset after a stable owner session",
            )
        }
    }

    private fun isCurrentReconnect(generation: Int): Boolean =
        synchronized(lock) {
            generation == reconnectGeneration && ownerBinder == null
        }

    private fun isOwnerAppProcessRunning(
        packageName: String,
        excludedOwnerPid: Int?,
    ): Boolean =
        File("/proc").listFiles()?.any { processDirectory ->
            val pid = processDirectory.name.toIntOrNull() ?: return@any false
            if (!isOwnerReconnectCandidatePid(
                processPid = pid,
                serverPid = android.os.Process.myPid(),
                excludedOwnerPid = excludedOwnerPid,
            )) {
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
        val crashLoopDecision: PrivilegeOwnerCrashLoopDecision,
        val plannedOwnerRestart: PrivilegePlannedOwnerRestart?,
    )

    private data class RuntimeConfig(
        val followDeathDelayMillis: Long,
        val activeReconnectOnOwnerDeath: Boolean,
    )

    private const val TAG = "PrivKitServer"
    private const val OWNER_RECONNECT_RETRY_DELAY_MILLIS = 1_000L
    private const val OWNER_RESTART_ARM_WINDOW_MILLIS = 5_000L
    private const val OWNER_CRASH_LOOP_WINDOW_MILLIS = 60_000L
    private const val OWNER_CRASH_LOOP_THRESHOLD = 3
}
