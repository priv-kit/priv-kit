package priv.kit

import android.os.IBinder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

public class PrivilegeUserServiceConnection internal constructor(
    private val id: String,
    public val binder: IBinder,
    private val unbind: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unbind(id)
        }
    }
}
