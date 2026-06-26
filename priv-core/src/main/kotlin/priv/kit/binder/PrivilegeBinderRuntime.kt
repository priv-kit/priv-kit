package priv.kit.binder

import android.os.IBinder

public object PrivilegeBinderRuntime {
    private val lock = Any()
    private var serverProvider: (() -> IPrivilegeServer)? = null

    @JvmSynthetic
    public fun installServerProvider(provider: () -> IPrivilegeServer) {
        synchronized(lock) {
            serverProvider = provider
        }
    }

    @JvmSynthetic
    public fun clearServerProvider() {
        synchronized(lock) {
            serverProvider = null
        }
    }

    internal fun requireServer(): IPrivilegeServer {
        val provider = synchronized(lock) {
            serverProvider
        } ?: throw PrivilegeServerDisconnectedException()
        val server = provider()
        if (!server.asBinder().pingBinder()) {
            throw PrivilegeServerDisconnectedException()
        }
        return server
    }

    internal fun requireServerBinder(): IBinder =
        requireServer().asBinder()
}
