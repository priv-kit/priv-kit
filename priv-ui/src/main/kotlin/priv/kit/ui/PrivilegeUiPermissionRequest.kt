package priv.kit.ui

import kotlinx.coroutines.CompletableDeferred

internal sealed class PrivilegeUiPermissionRequest(
    private val interactionPermit: AutoCloseable,
) : AutoCloseable {
    private val stateLock = Any()
    private var closed = false
    private var completionClaimed = false
    private var launchedHostId: String? = null
    private val completion = CompletableDeferred<Unit>()

    internal val wasLaunched: Boolean
        get() = synchronized(stateLock) { launchedHostId != null }

    internal fun tryMarkLaunched(hostId: String): Boolean =
        synchronized(stateLock) {
            if (closed || completionClaimed || launchedHostId != null) {
                false
            } else {
                launchedHostId = hostId
                true
            }
        }

    internal fun wasLaunchedBy(hostId: String): Boolean =
        synchronized(stateLock) { launchedHostId == hostId }

    internal fun tryClaimLaunchedCompletion(hostId: String): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == hostId)
        }

    internal fun tryClaimUnlaunchedCompletion(): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == null)
        }

    internal fun tryClaimCancellation(hostId: String): Boolean =
        synchronized(stateLock) {
            tryClaimCompletionLocked(launchedHostId == null || launchedHostId == hostId)
        }

    internal fun tryClaimCancellation(): Boolean =
        synchronized(stateLock) { tryClaimCompletionLocked(true) }

    internal suspend fun awaitCompletion() {
        completion.await()
    }

    final override fun close() {
        val shouldClose = synchronized(stateLock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (!shouldClose) return
        runCatching { interactionPermit.close() }
        completion.complete(Unit)
    }

    private fun tryClaimCompletionLocked(ownerMatches: Boolean): Boolean {
        if (closed || completionClaimed || !ownerMatches) return false
        completionClaimed = true
        return true
    }

    class Notification(
        interactionPermit: AutoCloseable,
    ) : PrivilegeUiPermissionRequest(interactionPermit)

    class LocalNetwork(
        val permission: String,
        interactionPermit: AutoCloseable,
    ) : PrivilegeUiPermissionRequest(interactionPermit)
}
