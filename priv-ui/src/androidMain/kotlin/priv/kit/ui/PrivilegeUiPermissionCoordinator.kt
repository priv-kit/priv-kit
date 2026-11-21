package priv.kit.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes VM-owned access flows and releases interaction permits on every exit. */
internal class PrivilegeUiPermissionCoordinator(
    private val acquireInteractivePermit: () -> AutoCloseable?,
    private val interactionsEnabled: () -> Boolean,
    private val ownerClosed: () -> Boolean,
    private val cancelPairingWithoutInteractionHost: () -> Unit,
    private val handler: suspend (PrivilegeUiAccessRequest) -> Boolean,
) : AutoCloseable {
    private val mutex = Mutex()
    private var hostAttached = false
    private var closed = false
    private var activeJob: Job? = null

    fun hasInteractionHost(): Boolean = hostAttached
    fun registerHost() { if (!closed && !ownerClosed()) hostAttached = true }

    fun unregisterHost(changingConfigurations: Boolean) {
        hostAttached = false
        if (!changingConfigurations) {
            activeJob?.cancel()
            cancelPairingWithoutInteractionHost()
        }
    }

    override fun close() {
        closed = true
        hostAttached = false
        activeJob?.cancel()
    }

    suspend fun requestNotificationPermission(): Boolean = request(PrivilegeUiAccessRequest.Permission.Notifications)
    suspend fun requestLocalNetworkPermission(): Boolean = request(PrivilegeUiAccessRequest.Permission.LocalNetwork)

    suspend fun request(request: PrivilegeUiAccessRequest): Boolean = mutex.withLock {
        if (closed || ownerClosed() || !hostAttached || !interactionsEnabled()) {
            throw CancellationException("Access host unavailable")
        }
        val permit = acquireInteractivePermit() ?: throw CancellationException("Interaction unavailable")
        try {
            coroutineScope {
                activeJob = currentCoroutineContext().job
                handler(request)
            }
        } finally {
            activeJob = null
            permit.close()
        }
    }
}
