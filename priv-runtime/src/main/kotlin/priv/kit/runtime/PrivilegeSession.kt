package priv.kit.runtime

import android.os.IBinder
import android.os.RemoteException
import priv.kit.core.IPrivilegeServer
import priv.kit.core.PrivilegeServerInfo

class PrivilegeSession internal constructor(
    val serverInfo: PrivilegeServerInfo,
    val serverBinder: IPrivilegeServer,
) {
    @Volatile
    var state: PrivilegeSessionState = PrivilegeSessionState.CONNECTED
        private set

    private val lock = Any()
    private var onDisconnected: ((PrivilegeSession) -> Unit)? = null

    private val deathRecipient = IBinder.DeathRecipient {
        markDisconnected()
    }

    init {
        try {
            serverBinder.asBinder().linkToDeath(deathRecipient, 0)
        } catch (_: RemoteException) {
            markDisconnected()
        }
    }

    fun setOnDisconnectedListener(listener: ((PrivilegeSession) -> Unit)?) {
        val notifyNow: Boolean
        synchronized(lock) {
            onDisconnected = listener
            notifyNow = state == PrivilegeSessionState.DISCONNECTED && listener != null
        }
        if (notifyNow) {
            listener?.invoke(this)
        }
    }

    fun close() {
        try {
            serverBinder.asBinder().unlinkToDeath(deathRecipient, 0)
        } catch (_: NoSuchElementException) {
        }
        markDisconnected()
    }

    @Throws(RemoteException::class)
    fun shutdown() {
        serverBinder.shutdown()
        close()
    }

    private fun markDisconnected() {
        val listener: ((PrivilegeSession) -> Unit)?
        synchronized(lock) {
            if (state == PrivilegeSessionState.DISCONNECTED) {
                return
            }
            state = PrivilegeSessionState.DISCONNECTED
            listener = onDisconnected
        }
        listener?.invoke(this)
    }
}
