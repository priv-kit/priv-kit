package priv.kit.ui.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.internal.runtime.PrivilegeRuntimeClientLaunch
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionOrigin
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartLease
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegeUiRuntimeStartSession(
    val generation: Long,
    val showAttemptFeedback: Boolean = true,
    val recordSuccessfulMethod: Boolean = true,
    private val onConnectionClaimed: () -> Unit = {},
    private val onConnectionHandled: () -> Unit = {},
    private val onOwnerCleanupRequired: () -> Unit = {},
    private val onOwnerCleanupCompleted: () -> Unit = {},
    private val startupLogSink: (PrivilegeUiRuntimeStartSession, String) -> Unit = { _, _ -> },
    private val structuredStartupLogSink: (PrivilegeUiRuntimeStartSession, PrivilegeStartupLogLine) -> Unit =
        { session, line ->
            startupLogSink(session, "[${line.source}] ${line.message}")
        },
) {
    private val lock = Any()
    private val closeables = mutableListOf<AutoCloseable>()
    private val cancellationRequestedState = AtomicBoolean(false)
    private val finishedState = AtomicBoolean(false)
    private val connectionClaimedState = AtomicBoolean(false)
    private val connectionHandledState = AtomicBoolean(false)
    private val runtimeStartJobCompletedState = AtomicBoolean(false)
    private val latestConnectedServerState = AtomicReference<PrivilegeServerInfo?>(null)
    private val committedStartState = AtomicReference<PrivilegeUiCommittedStart?>(null)
    private val runtimeStartLeaseState = AtomicReference<PrivilegeRuntimeStartLease?>(null)
    private val stopState = AtomicBoolean(false)
    private val completionSignal = CompletableDeferred<Unit>()
    private var resourceCleanupDone: CountDownLatch? = null

    val startupLogListener = PrivilegeStartupLogListener { line ->
        structuredStartupLogSink(this, line)
    }

    val cancellationRequested: Boolean
        get() = cancellationRequestedState.get()

    val finished: Boolean
        get() = finishedState.get()

    val stop: AtomicBoolean
        get() = stopState

    fun addCloseable(closeable: AutoCloseable) {
        val closeNow = synchronized(lock) {
            if (cancellationRequested || finished || connectionClaimed) {
                true
            } else {
                closeables += closeable
                false
            }
        }
        if (closeNow) {
            runCatching { closeable.close() }
        }
    }

    fun checkActive() {
        if (cancellationRequested || finished || connectionClaimed) {
            throw CancellationException("Runtime start session was cancelled")
        }
    }

    /** Records the exact method whose launch side effect is about to run. */
    fun commitStartMethod(method: PrivilegeUiStartMethod?) {
        synchronized(lock) {
            checkActive()
            val lease = checkNotNull(runtimeStartLeaseState.get()) {
                "Runtime start lease is not attached"
            }
            val launch = checkNotNull(PrivilegeRuntimeStartCoordinator.beginClientLaunch(lease)) {
                "Runtime start lease no longer owns the coordinator"
            }
            committedStartState.set(
                PrivilegeUiCommittedStart(
                    method = method,
                    launch = launch,
                ),
            )
        }
    }

    fun committedMethod(): PrivilegeUiStartMethod? = committedStartState.get()?.method

    fun requireRuntimeClientLaunch(): PrivilegeRuntimeClientLaunch =
        checkNotNull(committedStartState.get()?.launch) {
            "Runtime start method has not been committed"
        }

    val runtimeStartOperationId: Long?
        get() = runtimeStartLeaseState.get()?.operationId

    fun attachRuntimeStartLease(lease: PrivilegeRuntimeStartLease): Boolean {
        val accepted = synchronized(lock) {
            if (cancellationRequested || finished || connectionClaimed) {
                false
            } else {
                check(runtimeStartLeaseState.get() == null) {
                    "Runtime start lease is already attached"
                }
                runtimeStartLeaseState.set(lease)
                true
            }
        }
        if (!accepted) runCatching { lease.close() }
        return accepted
    }

    fun ownsRuntimeConnection(
        origin: PrivilegeRuntimeConnectionOrigin,
        clientStartOperationId: Long?,
        initialLaunchId: String?,
    ): Boolean =
        origin == PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH &&
            clientStartOperationId != null &&
            clientStartOperationId == runtimeStartOperationId &&
            initialLaunchId == committedStartState.get()?.launch?.initialLaunchId

    fun appendStartupLog(line: String) {
        startupLogSink(this, line)
    }

    fun markCancellationRequested(): Boolean {
        val changed = cancellationRequestedState.compareAndSet(false, true)
        if (changed) stopState.set(true)
        return changed
    }

    fun closeCancellationResources(onCloseFailure: (Throwable) -> Unit = {}) {
        closeResources(onCloseFailure)
    }

    fun finish(onCloseFailure: (Throwable) -> Unit = {}): Boolean {
        val firstFinish = finishedState.compareAndSet(false, true)
        if (firstFinish) {
            stopState.set(true)
            completionSignal.complete(Unit)
        }
        closeResources(onCloseFailure)
        releaseRuntimeStartLeaseIfReady()
        return firstFinish
    }

    fun recordConnectedServer(
        serverInfo: PrivilegeServerInfo,
        belongsToCurrentStart: Boolean,
    ): PrivilegeUiRuntimeConnectionClaim? {
        latestConnectedServerState.set(serverInfo)
        val claim = synchronized(lock) {
            if (!connectionClaimedState.compareAndSet(false, true)) return@synchronized null
            PrivilegeUiRuntimeConnectionClaim(
                successfulMethod = committedStartState.get()?.method.takeIf {
                    recordSuccessfulMethod &&
                        belongsToCurrentStart &&
                        !cancellationRequested
                },
            )
        }
        if (claim != null) onConnectionClaimed()
        return claim
    }

    fun markConnectionHandled() {
        if (connectionClaimed && connectionHandledState.compareAndSet(false, true)) {
            releaseRuntimeStartLeaseIfReady()
            onConnectionHandled()
        } else {
            releaseRuntimeStartLeaseIfReady()
        }
    }

    fun markRuntimeStartJobCompleted() {
        runtimeStartJobCompletedState.set(true)
        releaseRuntimeStartLeaseIfReady()
    }

    fun markOwnerCleanupRequired() {
        onOwnerCleanupRequired()
    }

    fun markOwnerCleanupCompleted() {
        onOwnerCleanupCompleted()
    }

    fun latestConnectedServer(): PrivilegeServerInfo? = latestConnectedServerState.get()

    fun recordDisconnectedServer() {
        latestConnectedServerState.set(null)
    }

    val connectionClaimed: Boolean
        get() = connectionClaimedState.get()

    fun signalCompletion() {
        completionSignal.complete(Unit)
    }

    suspend fun awaitCompletionSignal() {
        completionSignal.await()
    }

    private fun closeResources(onCloseFailure: (Throwable) -> Unit) {
        var cleanupOwner = false
        val cleanupDone: CountDownLatch
        val toClose: List<AutoCloseable>
        synchronized(lock) {
            val currentCleanup = resourceCleanupDone
            if (currentCleanup == null) {
                cleanupOwner = true
                cleanupDone = CountDownLatch(1)
                resourceCleanupDone = cleanupDone
                toClose = closeables.toList()
                closeables.clear()
            } else {
                cleanupDone = currentCleanup
                toClose = emptyList()
            }
        }
        if (!cleanupOwner) {
            cleanupDone.awaitUninterruptibly()
            return
        }

        try {
            toClose.forEach { closeable ->
                runCatching { closeable.close() }
                    .exceptionOrNull()
                    ?.let { throwable -> runCatching { onCloseFailure(throwable) } }
            }
        } finally {
            cleanupDone.countDown()
        }
    }

    private fun releaseRuntimeStartLeaseIfReady() {
        if (!runtimeStartJobCompletedState.get()) return
        if (
            (finished && !connectionClaimed) ||
            (connectionClaimed && connectionHandledState.get())
        ) {
            releaseRuntimeStartLease()
        }
    }

    private fun releaseRuntimeStartLease() {
        runtimeStartLeaseState.getAndSet(null)?.let { lease ->
            runCatching { lease.close() }
        }
    }

    private fun CountDownLatch.awaitUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

internal class PrivilegeUiRuntimeConnectionClaim(
    val successfulMethod: PrivilegeUiStartMethod?,
)

private data class PrivilegeUiCommittedStart(
    val method: PrivilegeUiStartMethod?,
    val launch: PrivilegeRuntimeClientLaunch,
)
