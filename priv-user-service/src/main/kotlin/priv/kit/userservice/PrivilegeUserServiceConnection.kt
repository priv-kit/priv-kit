package priv.kit.userservice

import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class PrivilegeUserServiceConnection internal constructor(
    val id: String,
    val spec: PrivilegeUserServiceSpec,
    val binder: IBinder,
    val status: PrivilegeUserServiceStatus,
    private val unbind: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unbind(id)
        }
    }
}
