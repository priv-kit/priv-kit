package priv.kit.ui

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegeUiPermissionCoordinator(
    private val coroutineScope: CoroutineScope,
    private val acquireInteractivePermit: () -> AutoCloseable?,
    private val interactionsEnabled: () -> Boolean,
    private val ownerClosed: () -> Boolean,
    private val handleNotificationPermissionResult: (PrivilegeUiPermissionState) -> Unit,
    private val cancelNotificationPermissionRequest: () -> Unit,
    private val cancelPairingWithoutInteractionHost: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val attachedHostIds = mutableSetOf<String>()
    private val detachedHostSerials = mutableMapOf<String, Long>()
    private val hostRebindJobs = mutableMapOf<String, Job>()
    private val hostDetachSerial = AtomicLong(0L)
    private val queuedRequests = ArrayDeque<PrivilegeUiPermissionRequest>()
    private val activeRequestState = MutableStateFlow<PrivilegeUiPermissionRequest?>(null)

    val requests: Flow<PrivilegeUiPermissionRequest> = activeRequestState.filterNotNull()

    fun hasInteractionHost(): Boolean =
        synchronized(lock) {
            attachedHostIds.isNotEmpty() || detachedHostSerials.isNotEmpty()
        }

    fun requestNotificationPermission(): Boolean {
        if (!interactionsEnabled()) return false
        val permit = acquireInteractivePermit() ?: return false
        return enqueue(PrivilegeUiPermissionRequest.Notification(permit))
    }

    fun requestLocalNetworkPermission(permission: String) {
        if (!interactionsEnabled()) return
        val permit = acquireInteractivePermit() ?: return
        enqueue(PrivilegeUiPermissionRequest.LocalNetwork(permission, permit))
    }

    fun completeNotificationPermissionRequest(
        hostId: String,
        permissionState: PrivilegeUiPermissionState,
    ) {
        val request = synchronized(lock) {
            val activeRequest =
                activeRequestState.value as? PrivilegeUiPermissionRequest.Notification
            activeRequest
                ?.takeIf { it.tryClaimLaunchedCompletion(hostId) }
                ?.also { advanceLocked() }
        } ?: return
        finishNotificationRequest(request, permissionState)
    }

    fun completeUnlaunchedNotificationPermissionRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest.Notification,
        permissionState: PrivilegeUiPermissionState,
    ) {
        val claimed = synchronized(lock) {
            if (
                hostId !in attachedHostIds ||
                activeRequestState.value !== request ||
                !request.tryClaimUnlaunchedCompletion()
            ) {
                false
            } else {
                advanceLocked()
                true
            }
        }
        if (claimed) finishNotificationRequest(request, permissionState)
    }

    fun completeLocalNetworkPermissionRequest(hostId: String) {
        val request = synchronized(lock) {
            val activeRequest =
                activeRequestState.value as? PrivilegeUiPermissionRequest.LocalNetwork
            activeRequest
                ?.takeIf { it.tryClaimLaunchedCompletion(hostId) }
                ?.also { advanceLocked() }
        } ?: return
        request.close()
    }

    fun registerHost(hostId: String) {
        val rebindJob = synchronized(lock) {
            if (isClosed()) return
            attachedHostIds += hostId
            detachedHostSerials.remove(hostId)
            hostRebindJobs.remove(hostId)
        }
        rebindJob?.cancel()
    }

    fun unregisterHost(
        hostId: String,
        changingConfigurations: Boolean,
    ) {
        if (changingConfigurations) {
            detachHostForConfigurationChange(hostId)
            return
        }
        val (requestsToCancel, noInteractionHostsRemain) = synchronized(lock) {
            if (!attachedHostIds.remove(hostId)) return
            val activeRequest = activeRequestState.value
            val noHostsRemain = attachedHostIds.isEmpty() && detachedHostSerials.isEmpty()
            val requests = when {
                noHostsRemain -> clearRequestsLocked()
                activeRequest?.wasLaunchedBy(hostId) == true -> {
                    if (activeRequest.tryClaimCancellation(hostId)) {
                        advanceLocked()
                        listOf(activeRequest)
                    } else {
                        emptyList()
                    }
                }
                else -> emptyList()
            }
            requests to noHostsRemain
        }
        finishHostCleanup(requestsToCancel, noInteractionHostsRemain)
    }

    fun completeRequest(request: PrivilegeUiPermissionRequest) {
        synchronized(lock) {
            request.tryClaimCancellation()
            if (activeRequestState.value === request) {
                advanceLocked()
            } else {
                queuedRequests.remove(request)
            }
        }
        request.close()
    }

    fun cancelRequest(
        hostId: String,
        request: PrivilegeUiPermissionRequest,
    ) {
        val removed = synchronized(lock) {
            if (
                activeRequestState.value !== request ||
                !request.tryClaimCancellation(hostId)
            ) {
                false
            } else {
                advanceLocked()
                true
            }
        }
        if (removed) cancelRequests(listOf(request))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val (requestsToClose, rebindJobsToCancel) = synchronized(lock) {
            attachedHostIds.clear()
            detachedHostSerials.clear()
            val rebindJobs = hostRebindJobs.values.toList()
            hostRebindJobs.clear()
            clearRequestsLocked() to rebindJobs
        }
        rebindJobsToCancel.forEach(Job::cancel)
        requestsToClose.forEach(PrivilegeUiPermissionRequest::close)
    }

    private fun finishNotificationRequest(
        request: PrivilegeUiPermissionRequest.Notification,
        permissionState: PrivilegeUiPermissionState,
    ) {
        request.use {
            handleNotificationPermissionResult(permissionState)
        }
    }

    private fun detachHostForConfigurationChange(hostId: String) {
        val serial = synchronized(lock) {
            if (!attachedHostIds.remove(hostId) || isClosed()) return
            hostRebindJobs.remove(hostId)?.cancel()
            hostDetachSerial.incrementAndGet().also { detachSerial ->
                detachedHostSerials[hostId] = detachSerial
            }
        }
        val rebindJob = coroutineScope.launch(
            CoroutineName("priv-ui-permission-host-rebind"),
        ) {
            delay(PERMISSION_HOST_REBIND_GRACE_MILLIS.milliseconds)
            expireDetachedHost(hostId, serial)
        }
        synchronized(lock) {
            if (detachedHostSerials[hostId] == serial && !isClosed()) {
                hostRebindJobs[hostId] = rebindJob
            } else {
                rebindJob.cancel()
            }
        }
    }

    private fun expireDetachedHost(
        hostId: String,
        serial: Long,
    ) {
        val (requestsToCancel, noInteractionHostsRemain) = synchronized(lock) {
            if (detachedHostSerials[hostId] != serial) return
            detachedHostSerials.remove(hostId)
            hostRebindJobs.remove(hostId)
            val noHostsRemain = attachedHostIds.isEmpty() && detachedHostSerials.isEmpty()
            val requests = buildList {
                val activeRequest = activeRequestState.value
                if (
                    activeRequest?.wasLaunchedBy(hostId) == true &&
                    activeRequest.tryClaimCancellation(hostId)
                ) {
                    advanceLocked()
                    add(activeRequest)
                }
                if (noHostsRemain) addAll(clearRequestsLocked())
            }.distinct()
            requests to noHostsRemain
        }
        finishHostCleanup(requestsToCancel, noInteractionHostsRemain)
    }

    private fun enqueue(request: PrivilegeUiPermissionRequest): Boolean {
        synchronized(lock) {
            if (isClosed() || attachedHostIds.isEmpty()) {
                request.close()
                return false
            }
            if (activeRequestState.value == null) {
                activeRequestState.value = request
            } else {
                queuedRequests += request
            }
        }
        return true
    }

    private fun advanceLocked() {
        activeRequestState.value = queuedRequests.removeFirstOrNull()
    }

    private fun clearRequestsLocked(): List<PrivilegeUiPermissionRequest> =
        buildList {
            activeRequestState.value?.let(::add)
            addAll(queuedRequests)
        }.also { requests ->
            requests.forEach { request -> request.tryClaimCancellation() }
            activeRequestState.value = null
            queuedRequests.clear()
        }

    private fun cancelRequests(requests: List<PrivilegeUiPermissionRequest>) {
        if (requests.any { it is PrivilegeUiPermissionRequest.Notification }) {
            cancelNotificationPermissionRequest()
        }
        requests.forEach(PrivilegeUiPermissionRequest::close)
    }

    private fun finishHostCleanup(
        requests: List<PrivilegeUiPermissionRequest>,
        noInteractionHostsRemain: Boolean,
    ) {
        cancelRequests(requests)
        if (noInteractionHostsRemain) cancelPairingWithoutInteractionHost()
    }

    private fun isClosed(): Boolean = closed.get() || ownerClosed()
}

private const val PERMISSION_HOST_REBIND_GRACE_MILLIS = 10_000L
