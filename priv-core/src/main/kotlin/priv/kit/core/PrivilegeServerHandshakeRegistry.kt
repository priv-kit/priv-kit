package priv.kit.core

import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

public object PrivilegeServerHandshakeRegistry {
    private val pendingHandshakes = ConcurrentHashMap<String, PrivilegePendingHandshake>()
    private val readyHandshakes = ConcurrentHashMap<String, PrivilegeServerHandshakeResult>()
    private val readyListeners = ConcurrentHashMap<String, CopyOnWriteArraySet<(PrivilegeServerHandshakeResult) -> Unit>>()

    public fun prepare(token: String): PrivilegePendingHandshake {
        require(token.isNotBlank()) { "token must not be blank" }
        val handshake = PrivilegePendingHandshake(token)
        val previous = pendingHandshakes.putIfAbsent(token, handshake)
        require(previous == null) { "token is already pending" }
        readyHandshakes.remove(token)?.let(handshake::complete)
        return handshake
    }

    public fun deliver(
        token: String?,
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
    ): Boolean {
        if (token.isNullOrBlank() || serverBinder == null) {
            return false
        }
        val result = PrivilegeServerHandshakeResult(
            token = token,
            serverInfo = serverInfo,
            serverBinder = serverBinder,
        )
        val handshake = pendingHandshakes.remove(token) ?: return false
        handshake.complete(result)
        return true
    }

    public fun deliverReady(
        token: String?,
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
    ): Boolean {
        if (token.isNullOrBlank() || serverBinder == null) {
            return false
        }
        val result = PrivilegeServerHandshakeResult(
            token = token,
            serverInfo = serverInfo,
            serverBinder = serverBinder,
        )
        val handshake = pendingHandshakes.remove(token)
        if (handshake != null) {
            handshake.complete(result)
            return true
        }

        val listeners = readyListeners[token]
        if (listeners.isNullOrEmpty()) {
            readyHandshakes[token] = result
        } else {
            listeners.forEach { listener -> listener(result) }
        }
        return true
    }

    public fun claimReady(token: String): PrivilegeServerHandshakeResult? {
        require(token.isNotBlank()) { "token must not be blank" }
        return readyHandshakes.remove(token)
    }

    public fun addReadyListener(
        token: String,
        listener: (PrivilegeServerHandshakeResult) -> Unit,
    ): Closeable {
        require(token.isNotBlank()) { "token must not be blank" }
        val listeners = readyListeners.computeIfAbsent(token) { CopyOnWriteArraySet() }
        listeners += listener
        readyHandshakes.remove(token)?.let(listener)
        return Closeable {
            listeners -= listener
            if (listeners.isEmpty()) {
                readyListeners.remove(token, listeners)
            }
        }
    }

    public fun cancel(token: String) {
        pendingHandshakes.remove(token)
    }
}
