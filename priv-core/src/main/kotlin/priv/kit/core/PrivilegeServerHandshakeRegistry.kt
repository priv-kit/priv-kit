package priv.kit.core

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

object PrivilegeServerHandshakeRegistry {
    private val pendingHandshakes = ConcurrentHashMap<String, PrivilegePendingHandshake>()

    fun prepare(token: String): PrivilegePendingHandshake {
        require(token.isNotBlank()) { "token must not be blank" }
        val handshake = PrivilegePendingHandshake(token)
        val previous = pendingHandshakes.putIfAbsent(token, handshake)
        require(previous == null) { "token is already pending" }
        return handshake
    }

    fun deliver(
        token: String?,
        serverBinder: IBinder?,
        serverInfo: PrivilegeServerInfo,
    ): Boolean {
        if (token.isNullOrBlank() || serverBinder == null) {
            return false
        }
        val handshake = pendingHandshakes.remove(token) ?: return false
        handshake.complete(
            PrivilegeServerHandshakeResult(
                token = token,
                serverInfo = serverInfo,
                serverBinder = serverBinder,
            ),
        )
        return true
    }

    fun cancel(token: String) {
        pendingHandshakes.remove(token)
    }
}
