package priv.kit.internal.binder

import android.os.IBinder
import priv.kit.binder.PrivilegeServerDisconnectedException

internal object PrivilegeBinderRuntime {
    private val lock = Any()
    private var serverProvider: (() -> IPrivilegeServer)? = null

    @JvmSynthetic
    fun installServerProvider(provider: () -> IPrivilegeServer) {
        synchronized(lock) {
            serverProvider = provider
        }
    }

    @JvmSynthetic
    fun clearServerProvider() {
        synchronized(lock) {
            serverProvider = null
        }
    }

    internal fun requireServer(): IPrivilegeServer {
        val provider = synchronized(lock) {
            serverProvider
        } ?: throw PrivilegeServerDisconnectedException()
        return provider()
    }

    internal fun requireServerBinder(): IBinder =
        requireServer().asBinder()
}
