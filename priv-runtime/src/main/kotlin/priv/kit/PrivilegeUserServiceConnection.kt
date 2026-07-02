package priv.kit

import android.os.IBinder
import android.os.RemoteException
import priv.kit.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

public class PrivilegeUserServiceConnection internal constructor(
    public val id: String,
    public val spec: PrivilegeUserServiceSpec,
    public val binder: IBinder,
    private val unbind: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    @Throws(RemoteException::class)
    public fun <T> call(block: (IBinder) -> T): T =
        block(binder)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unbind(id)
        }
    }
}
