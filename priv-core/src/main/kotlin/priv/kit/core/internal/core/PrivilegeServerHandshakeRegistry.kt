package priv.kit.core.internal.core

import android.os.IBinder
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import java.io.Closeable

internal object PrivilegeServerHandshakeRegistry {
    private val lock = Any()
    private val pendingHandshakesByLaunchId = mutableMapOf<String, PendingHandshakeEntry>()
    private val readyHandshakes = mutableMapOf<String, PrivilegeServerHandshakeResult>()
    private val latestReadyDeliverySerials = mutableMapOf<String, Long>()
    private val readyListeners =
        mutableMapOf<String, MutableSet<(PrivilegeServerHandshakeResult) -> Boolean>>()

    fun prepare(
        token: String,
        initialLaunchId: String,
    ): PrivilegePendingHandshake {
        require(token.isNotBlank()) { "token must not be blank" }
        require(initialLaunchId.isNotBlank()) { "initialLaunchId must not be blank" }
        val handshake = PrivilegePendingHandshake()
        synchronized(lock) {
            check(
                pendingHandshakesByLaunchId.put(
                    initialLaunchId,
                    PendingHandshakeEntry(token, handshake),
                ) == null,
            ) {
                "initialLaunchId is already pending"
            }
            readyHandshakes[token]
                ?.takeIf {
                    it.origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH &&
                        it.initialLaunchId == initialLaunchId
                }
                ?.also { readyHandshakes.remove(token) }
                ?.let { ready -> check(handshake.complete(ready)) }
        }
        return handshake
    }

    fun deliverReady(
        token: String?,
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
        origin: PrivilegeServerHandshakeOrigin,
        initialLaunchId: String?,
    ): Boolean {
        if (token.isNullOrBlank() || serverBinder == null) {
            return false
        }
        val ticket = PrivilegeRuntimeStartCoordinator.tryAcceptHandshake(
            origin = origin,
            initialLaunchId = initialLaunchId,
        )
            ?: return false
        val result = PrivilegeServerHandshakeResult(
            serverInfo = serverInfo,
            serverBinder = serverBinder,
            origin = origin,
            initialLaunchId = initialLaunchId,
            clientStartOperationId = ticket.clientStartOperationId,
            deliverySerial = ticket.serial,
        )
        try {
            var pendingHandshake: PrivilegePendingHandshake? = null
            var duplicatePendingHandshake = false
            var listeners: List<(PrivilegeServerHandshakeResult) -> Boolean> = emptyList()
            synchronized(lock) {
                if (origin == PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH) {
                    pendingHandshake = initialLaunchId
                        ?.let(pendingHandshakesByLaunchId::get)
                        ?.takeIf { it.token == token }
                        ?.handshake
                }
                if (pendingHandshake != null) {
                    duplicatePendingHandshake = pendingHandshake.complete(result) == false
                } else {
                    listeners = readyListeners[token]?.toList().orEmpty()
                }
            }
            if (duplicatePendingHandshake) return false
            val pending = pendingHandshake
            if (pending == null) {
                deliverToListenersOrCache(token, result, listeners)
            }
            PrivilegeRuntimeStartCoordinator.notifyServerHandshakeAccepted(ticket)
            return true
        } finally {
            PrivilegeRuntimeStartCoordinator.finishHandshake(ticket)
        }
    }

    fun claimReady(token: String): PrivilegeServerHandshakeResult? {
        require(token.isNotBlank()) { "token must not be blank" }
        return synchronized(lock) {
            readyHandshakes.remove(token)
        }
    }

    fun addReadyListener(
        token: String,
        listener: (PrivilegeServerHandshakeResult) -> Boolean,
    ): Closeable {
        require(token.isNotBlank()) { "token must not be blank" }
        val ready = synchronized(lock) {
            readyListeners.getOrPut(token) { linkedSetOf() } += listener
            readyHandshakes.remove(token)
        }
        ready?.let { result ->
            deliverToListenersOrCache(token, result, listOf(listener))
        }
        return Closeable {
            synchronized(lock) {
                val listeners = readyListeners[token] ?: return@synchronized
                listeners -= listener
                if (listeners.isEmpty()) {
                    readyListeners.remove(token)
                }
            }
        }
    }

    fun acknowledge(initialLaunchId: String) {
        synchronized(lock) {
            pendingHandshakesByLaunchId.remove(initialLaunchId)
        }
    }

    /** Returns whether a delivered, unacknowledged server was preserved for ready handoff. */
    fun cancel(initialLaunchId: String): Boolean {
        var result: PrivilegeServerHandshakeResult? = null
        var token: String? = null
        var listeners: List<(PrivilegeServerHandshakeResult) -> Boolean> = emptyList()
        synchronized(lock) {
            val pending = pendingHandshakesByLaunchId.remove(initialLaunchId) ?: return false
            result = pending.handshake.completedResultOrNull() ?: return false
            token = pending.token
            listeners = readyListeners[pending.token]?.toList().orEmpty()
        }
        val ready = requireNotNull(result)
        deliverToListenersOrCache(
            token = requireNotNull(token),
            result = ready,
            listeners = listeners,
        )
        return true
    }

    private fun deliverToListenersOrCache(
        token: String,
        result: PrivilegeServerHandshakeResult,
        listeners: List<(PrivilegeServerHandshakeResult) -> Boolean>,
    ) {
        val isLatestDelivery = synchronized(lock) {
            val latestSerial = latestReadyDeliverySerials[token]
            if (latestSerial != null && result.deliverySerial < latestSerial) {
                false
            } else {
                latestReadyDeliverySerials[token] = result.deliverySerial
                readyHandshakes.remove(token)
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
            if (latestReadyDeliverySerials[token] == result.deliverySerial) {
                readyHandshakes[token] = result
            }
        }
    }

    private data class PendingHandshakeEntry(
        val token: String,
        val handshake: PrivilegePendingHandshake,
    )
}
