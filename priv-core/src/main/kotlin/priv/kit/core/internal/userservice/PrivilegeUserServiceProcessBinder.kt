package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.DeadObjectException
import android.os.Process
import android.os.Handler
import android.os.Looper
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import kotlin.system.exitProcess
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceProcessBinder(
    private val serviceClassName: String?,
    instance: Any,
) : IPrivilegeUserServiceProcess.Stub() {
    private val forwarder = PrivilegeBinderForwarder()
    private val binder = if (serviceClassName == null) forwarder else
        PrivilegeUserServiceRegistry.binderFrom(instance, serviceClassName)
    private val destroyed = AtomicBoolean(false)
    private var serverLifecycle: IBinder? = null

    override fun getForwardingBinder(): IBinder {
        ensureAlive()
        return forwarder
    }

    override fun getUid(): Int = Process.myUid()

    override fun attachServer(lifecycle: IBinder) {
        lifecycle.linkToDeath({ exitProcess(0) }, 0)
        serverLifecycle = lifecycle
    }

    override fun start() {
        ensureAlive()
    }

    override fun bind(): IBinder {
        ensureAlive()
        return binder
    }

    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            try {
                if (serviceClassName != null) PrivilegeUserServiceDestroyer.destroy(binder)
            } finally {
                Handler(Looper.getMainLooper()).post { exitProcess(0) }
            }
        }
    }

    private fun ensureAlive() {
        if (destroyed.get()) {
            throw DeadObjectException("UserService was destroyed: $serviceClassName")
        }
    }
}
