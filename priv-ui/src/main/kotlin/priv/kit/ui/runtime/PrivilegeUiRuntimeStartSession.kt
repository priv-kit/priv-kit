package priv.kit.ui.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import priv.kit.PrivilegeServerInfo
import priv.kit.PrivilegeStartupLogLine
import priv.kit.PrivilegeStartupLogListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegeUiRuntimeStartSession(
    val generation: Long,
    val showAttemptFeedback: Boolean = true,
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
    private val latestConnectedServerState = AtomicReference<PrivilegeServerInfo?>(null)
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
        return firstFinish
    }

    fun recordConnectedServer(serverInfo: PrivilegeServerInfo): Boolean {
        latestConnectedServerState.set(serverInfo)
        return connectionClaimedState.compareAndSet(false, true)
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
