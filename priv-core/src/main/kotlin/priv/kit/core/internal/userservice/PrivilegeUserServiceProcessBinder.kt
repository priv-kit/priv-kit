package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.Handler
import android.os.Looper
import kotlin.system.exitProcess
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceProcessBinder(
    private val binder: IBinder?,
) : IPrivilegeUserServiceProcess.Stub() {
    private val bindings = PrivilegeUserServiceBindings(binder)
    private val destroyed = AtomicBoolean(false)
    private var serverLifecycle: IBinder? = null

    override fun attachServer(lifecycle: IBinder) {
        lifecycle.linkToDeath({ exitProcess(0) }, 0)
        serverLifecycle = lifecycle
    }

    override fun bind(connectionId: String): IBinder = bindings.bind(connectionId)

    override fun unbind(connectionId: String) = bindings.unbind(connectionId)

    override fun closeBindings() = bindings.close()

    override fun destroy() {
        if (destroyed.compareAndSet(false, true)) {
            bindings.close()
            try {
                binder?.let(PrivilegeUserServiceDestroyer::destroy)
            } finally {
                Handler(Looper.getMainLooper()).post { exitProcess(0) }
            }
        }
    }

}
