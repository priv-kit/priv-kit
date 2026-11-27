package priv.kit.core

import android.os.IBinder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import priv.kit.core.internal.userservice.PrivilegeUserServiceBinder
import priv.kit.core.internal.userservice.PrivilegeUserServiceConnectionState

public class PrivilegeUserServiceConnection internal constructor(
    binder: IBinder,
    private val unbindAction: suspend () -> Unit,
    private val connectionState: PrivilegeUserServiceConnectionState = PrivilegeUserServiceConnectionState(),
) {
    private val unbindMutex = Mutex()
    private val endpoint = binder
    public val binder: IBinder = PrivilegeUserServiceBinder(binder, connectionState)

    /**
     * Whether this connection is still active. Becomes false on unbind, service stop or death.
     * Remote disconnect notifications arrive asynchronously. A disconnected connection never
     * reconnects; bind or peek again to obtain a new connection.
     */
    public val isConnected: StateFlow<Boolean> = connectionState.isConnected

    internal fun <T> callForwarder(call: (IBinder) -> T): T = connectionState.call { call(endpoint) }

    internal fun requireForwardingBinder(): IBinder = callForwarder { it }

    public suspend fun unbind() {
        withContext(NonCancellable) {
            unbindMutex.withLock {
                if (isConnected.value) {
                    unbindAction()
                    connectionState.disconnect()
                }
            }
        }
    }
}
