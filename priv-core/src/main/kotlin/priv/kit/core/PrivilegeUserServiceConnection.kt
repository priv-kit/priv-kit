package priv.kit.core

import android.os.IBinder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public class PrivilegeUserServiceConnection internal constructor(
    public val binder: IBinder,
    private val unbindAction: suspend () -> Unit,
) {
    private val unbindMutex = Mutex()
    private var unbound = false

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
