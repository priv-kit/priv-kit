package priv.kit.core.internal.userservice

import android.os.IBinder
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import java.io.Closeable

/** Each connection has one gate for custom AIDL calls and Binder forwarding. */
internal class PrivilegeUserServiceBindings(
    private val service: IBinder? = null,
    private val forwarder: PrivilegeBinderForwarder = PrivilegeBinderForwarder(),
) : Closeable {
    private val bindings = mutableMapOf<String, PrivilegeUserServiceGateBinder>()
    private var closed = false

    @Synchronized
    fun bind(connectionId: String): IBinder {
        check(!closed) { "UserService bindings are closed" }
        return bindings.getOrPut(connectionId) { PrivilegeUserServiceGateBinder(service, forwarder) }
    }

    @Synchronized
    fun unbind(connectionId: String) {
        bindings.remove(connectionId)?.close()
    }

    @Synchronized
    override fun close() {
        closed = true
        bindings.values.forEach { it.close() }
        bindings.clear()
    }

}
