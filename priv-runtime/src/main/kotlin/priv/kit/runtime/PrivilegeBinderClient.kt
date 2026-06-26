package priv.kit.runtime

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderEndpoint
import priv.kit.binder.PrivilegeBinderEndpointDeadException
import priv.kit.binder.PrivilegeBinderEndpointNotFoundException
import priv.kit.binder.PrivilegeBinderRemoteCallException
import priv.kit.binder.PrivilegeServerDisconnectedException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeBinderClient internal constructor(
    private val serverProvider: () -> IPrivilegeServer,
) {
    fun register(binder: IBinder): Closeable {
        if (!binder.pingBinder()) {
            throw PrivilegeBinderEndpointDeadException()
        }

        callServer("register Binder endpoint") {
            it.registerBinderEndpoint(binder)
        }
        return CloseOnce {
            unregister()
        }
    }

    fun register(endpoint: PrivilegeBinderEndpoint): Closeable =
        register(endpoint.asBinder())

    fun get(): PrivilegeBinderEndpoint? {
        val binder = callServer("get Binder endpoint") {
            it.getBinderEndpoint()
        } ?: return null
        return PrivilegeBinderEndpoint(binder)
    }

    fun require(): PrivilegeBinderEndpoint =
        get() ?: throw PrivilegeBinderEndpointNotFoundException()

    fun unregister(): Boolean =
        callServer("unregister Binder endpoint") {
            it.unregisterBinderEndpoint()
        }

    private inline fun <T> callServer(
        operation: String,
        block: (IPrivilegeServer) -> T,
    ): T {
        val server = serverProvider()
        if (!server.asBinder().pingBinder()) {
            throw PrivilegeServerDisconnectedException()
        }
        return try {
            block(server)
        } catch (exception: DeadObjectException) {
            throw PrivilegeServerDisconnectedException(
                "Privilege server Binder died while trying to $operation",
                exception,
            )
        } catch (exception: RemoteException) {
            throw PrivilegeBinderRemoteCallException("Failed to $operation", exception)
        }
    }
}

private class CloseOnce(
    private val closeAction: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeAction()
        }
    }
}
