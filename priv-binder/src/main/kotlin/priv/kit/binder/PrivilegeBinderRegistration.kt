package priv.kit.binder

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

public class PrivilegeBinderRegistration internal constructor(
    private val unregister: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unregister()
        }
    }
}
