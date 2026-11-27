package priv.kit.core.internal.userservice

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.os.ResultReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import priv.kit.core.binder.PrivilegeUnavailableException

/** One notification receiver per binding, including while its result is still in flight. */
internal class PrivilegeUserServiceConnectionState : ResultReceiver(null) {
    private val connected = MutableStateFlow(true)
    val isConnected = connected.asStateFlow()
    private val lock = Any()
    private var server: IBinder? = null
    private val serverDeath = IBinder.DeathRecipient { disconnect() }

    fun observeServer(binder: IBinder) {
        synchronized(lock) {
            if (!connected.value) return
            server = binder
            try {
                binder.linkToDeath(serverDeath, 0)
            } catch (_: RemoteException) {
                disconnect()
            }
        }
    }

    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
        disconnect()
    }

    fun <T> call(block: () -> T): T {
        if (!connected.value) throw PrivilegeUnavailableException("Privilege connection is closed")
        return try {
            block()
        } catch (exception: PrivilegeUnavailableException) {
            disconnect()
            throw exception
        }
    }

    fun disconnect() {
        val previous = synchronized(lock) {
            connected.value = false
            server.also { server = null }
        }
        previous?.let { runCatching { it.unlinkToDeath(serverDeath, 0) } }
    }
}
