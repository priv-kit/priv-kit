package priv.kit.internal.userservice

import android.os.IBinder
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceProcessBinder(
    private val serviceClassName: String,
    instance: Any,
) : IPrivilegeUserServiceProcess.Stub() {
    private val binder = PrivilegeUserServiceRegistry.binderFrom(instance, serviceClassName)
    private val destroyed = AtomicBoolean(false)

    override fun start() {
        ensureAlive()
    }

    override fun bind(): IBinder {
        ensureAlive()
        return binder
    }

    override fun unbind(connectionId: String) = Unit

    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            PrivilegeUserServiceDestroyer.destroy(binder)
        }
    }

    private fun ensureAlive() {
        if (destroyed.get()) {
            throw PrivilegeUserServiceNotRunningException("UserService was destroyed: $serviceClassName")
        }
    }
}
