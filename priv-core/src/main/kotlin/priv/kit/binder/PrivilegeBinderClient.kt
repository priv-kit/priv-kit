package priv.kit.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException

public class PrivilegeBinderClient public constructor() {
    public fun register(binder: IBinder): PrivilegeBinderRegistration {
        if (!binder.pingBinder()) {
            throw PrivilegeBinderEndpointDeadException()
        }

        callServer("register Binder endpoint") {
            it.registerBinderEndpoint(binder)
        }
        return PrivilegeBinderRegistration {
            unregister()
        }
    }

    public fun register(endpoint: PrivilegeBinderEndpoint): PrivilegeBinderRegistration =
        register(endpoint.asBinder())

    public fun get(): PrivilegeBinderEndpoint? {
        val binder = callServer("get Binder endpoint") {
            it.getBinderEndpoint()
        } ?: return null
        return PrivilegeBinderEndpoint(binder)
    }

    public fun require(): PrivilegeBinderEndpoint =
        get() ?: throw PrivilegeBinderEndpointNotFoundException()

    public fun unregister(): Boolean =
        callServer("unregister Binder endpoint") {
            it.unregisterBinderEndpoint()
        }

    private inline fun <T> callServer(
        operation: String,
        block: (IPrivilegeServer) -> T,
    ): T {
        val server = PrivilegeBinderRuntime.requireServer()
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
