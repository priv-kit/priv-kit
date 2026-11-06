package priv.kit.core

import android.os.IBinder
import android.os.DeadObjectException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public class PrivilegeUserServiceConnection internal constructor(
    public val binder: IBinder,
    private val forwardingBinder: IBinder,
    internal val lifecycleBinder: IBinder,
    public val uid: Int,
    private val unbindAction: suspend () -> Unit,
) {
    private val unbindMutex = Mutex()
    @Volatile private var unbound = false

    /** Package identity for system calls; this does not change the host application's Context. */
    public val callingPackageName: String?
        get() = when (uid) {
            1000 -> "android"
            2000 -> "com.android.shell"
            else -> null
        }

    internal fun requireForwardingBinder(): IBinder {
        if (unbound) throw DeadObjectException("UserService connection has been unbound")
        return forwardingBinder
    }

    internal fun isForwardingAlive(): Boolean = !unbound && lifecycleBinder.isBinderAlive

    public suspend fun unbind() {
        withContext(NonCancellable) {
            unbindMutex.withLock {
                if (!unbound) {
                    unbindAction()
                    unbound = true
                }
            }
        }
    }
}
