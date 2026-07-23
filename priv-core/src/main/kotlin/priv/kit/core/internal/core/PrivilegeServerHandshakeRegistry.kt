package priv.kit.core.internal.core

import android.os.IBinder
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import java.io.Closeable

internal object PrivilegeServerHandshakeRegistry {
    private val lock = Any()
    private val pendingHandshakesByCorrelationId = mutableMapOf<String, PrivilegePendingHandshake>()
    private var readyHandshake: PrivilegeServerHandshakeResult? = null
    private var latestReadyDeliverySerial: Long? = null
    private val readyListeners =
        linkedSetOf<(PrivilegeServerHandshakeResult) -> Boolean>()

    fun prepare(launchCorrelationId: String): PrivilegePendingHandshake {
        require(launchCorrelationId.isNotBlank()) { "launchCorrelationId must not be blank" }
        val handshake = PrivilegePendingHandshake()
        synchronized(lock) {
            check(
                pendingHandshakesByCorrelationId.put(
                    launchCorrelationId,
                    handshake,
                ) == null,
            ) {
                "launchCorrelationId is already pending"
            }
            readyHandshake
                ?.takeIf {
                    it.origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH &&
                        it.launchCorrelationId == launchCorrelationId
                }
                ?.also { readyHandshake = null }
                ?.let { ready -> check(handshake.complete(ready)) }
        }
        return handshake
    }

    fun deliverReady(
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
        origin: PrivilegeServerHandshakeOrigin,
        launchCorrelationId: String?,
    ): Boolean {
        if (serverBinder == null) {
            return false
        }
        val ticket = PrivilegeRuntimeStartCoordinator.tryAcceptHandshake(
            origin = origin,
            launchCorrelationId = launchCorrelationId,
        )
            ?: return false
        val result = PrivilegeServerHandshakeResult(
            serverInfo = serverInfo,
            serverBinder = serverBinder,
            origin = origin,
            launchCorrelationId = launchCorrelationId,
            clientStartOperationId = ticket.clientStartOperationId,
            deliverySerial = ticket.serial,
        )
        try {
            var pendingHandshake: PrivilegePendingHandshake? = null
            var duplicatePendingHandshake = false
            var listeners: List<(PrivilegeServerHandshakeResult) -> Boolean> = emptyList()
            synchronized(lock) {
                if (origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH) {
                    pendingHandshake = launchCorrelationId
                        ?.let(pendingHandshakesByCorrelationId::get)
                }
                if (pendingHandshake != null) {
                    duplicatePendingHandshake = pendingHandshake.complete(result) == false
                } else {
                    listeners = readyListeners.toList()
                }
            }
            if (duplicatePendingHandshake) return false
            val pending = pendingHandshake
            if (pending == null) {
                deliverToListenersOrCache(result, listeners)
            }
            PrivilegeRuntimeStartCoordinator.notifyServerHandshakeAccepted(ticket)
            return true
        } finally {
            PrivilegeRuntimeStartCoordinator.finishHandshake(ticket)
        }
    }

    fun claimReady(): PrivilegeServerHandshakeResult? =
        synchronized(lock) {
            readyHandshake.also { readyHandshake = null }
        }

    fun addReadyListener(
        listener: (PrivilegeServerHandshakeResult) -> Boolean,
    ): Closeable {
        val ready = synchronized(lock) {
            readyListeners += listener
            readyHandshake.also { readyHandshake = null }
        }
        ready?.let { result ->
            deliverToListenersOrCache(result, listOf(listener))
        }
        return Closeable {
            synchronized(lock) {
                readyListeners -= listener
            }
        }
    }

    fun acknowledge(launchCorrelationId: String) {
        synchronized(lock) {
            pendingHandshakesByCorrelationId.remove(launchCorrelationId)
        }
    }

    /** Returns whether a delivered, unacknowledged server was preserved for ready handoff. */
    fun cancel(launchCorrelationId: String): Boolean {
        var result: PrivilegeServerHandshakeResult? = null
        var listeners: List<(PrivilegeServerHandshakeResult) -> Boolean> = emptyList()
        synchronized(lock) {
            val pending = pendingHandshakesByCorrelationId.remove(launchCorrelationId) ?: return false
            result = pending.completedResultOrNull() ?: return false
            listeners = readyListeners.toList()
        }
        val ready = requireNotNull(result)
        deliverToListenersOrCache(
            result = ready,
            listeners = listeners,
        )
        return true
    }

    private fun deliverToListenersOrCache(
        result: PrivilegeServerHandshakeResult,
        listeners: List<(PrivilegeServerHandshakeResult) -> Boolean>,
    ) {
        val isLatestDelivery = synchronized(lock) {
            val latestSerial = latestReadyDeliverySerial
            if (latestSerial != null && result.deliverySerial < latestSerial) {
                false
            } else {
                latestReadyDeliverySerial = result.deliverySerial
                readyHandshake = null
                true
            }
        }
        if (!isLatestDelivery) return
        var delivered = false
        listeners.forEach { listener ->
            if (runCatching { listener(result) }.getOrDefault(false)) {
                delivered = true
            }
        }
        if (delivered) return
        synchronized(lock) {
            if (latestReadyDeliverySerial == result.deliverySerial) {
                readyHandshake = result
            }
        }
    }
}
