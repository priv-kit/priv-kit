package priv.kit.ui

import kotlinx.coroutines.CompletableDeferred

internal sealed class PrivilegeUiPermissionRequest(
    private val interactionPermit: AutoCloseable,
) : AutoCloseable {
    private val lock = Any()
    private val completion = CompletableDeferred<PrivilegeUiPermissionState?>()
    private var launchedHostId: String? = null
    private var finished = false

    internal val wasLaunched: Boolean
        get() = synchronized(lock) { launchedHostId != null }

    internal fun tryMarkLaunched(hostId: String): Boolean =
        synchronized(lock) {
            if (finished || launchedHostId != null) false
            else {
                launchedHostId = hostId
                true
            }
        }

    internal fun wasLaunchedBy(hostId: String): Boolean =
        synchronized(lock) { launchedHostId == hostId }

    internal fun complete(
        hostId: String,
        permissionState: PrivilegeUiPermissionState,
    ) {
        finish(permissionState) { launchedHostId == hostId }
    }

    internal fun completeUnlaunched(permissionState: PrivilegeUiPermissionState) {
        finish(permissionState) { launchedHostId == null }
    }

    internal fun cancel(hostId: String) {
        finish(null) { launchedHostId == null || launchedHostId == hostId }
    }

    internal suspend fun awaitCompletion(): PrivilegeUiPermissionState? = completion.await()

    final override fun close() {
        finish(null) { true }
    }

    private fun finish(
        result: PrivilegeUiPermissionState?,
        canFinish: () -> Boolean,
    ) {
        val claimed = synchronized(lock) {
            if (finished || !canFinish()) false
            else {
                finished = true
                true
            }
        }
        if (!claimed) return
        completion.complete(result)
        runCatching { interactionPermit.close() }
    }

    class Notification(interactionPermit: AutoCloseable) :
        PrivilegeUiPermissionRequest(interactionPermit)

    class LocalNetwork(
        val permission: String,
        interactionPermit: AutoCloseable,
    ) : PrivilegeUiPermissionRequest(interactionPermit)
}
