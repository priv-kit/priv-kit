package priv.kit.binder

import android.os.IBinder
import android.os.RemoteException
import java.io.Closeable

class PrivilegeBinderEndpoint(
    private val binder: IBinder,
) {
    val isAlive: Boolean
        get() = binder.pingBinder()

    fun asBinder(): IBinder = binder

    fun watchDeath(onDied: (PrivilegeBinderEndpoint) -> Unit): Closeable {
        val deathRecipient = IBinder.DeathRecipient {
            onDied(this)
        }
        try {
            binder.linkToDeath(deathRecipient, 0)
        } catch (exception: RemoteException) {
            throw PrivilegeBinderEndpointDeadException(exception)
        }
        return Closeable {
            runCatching {
                binder.unlinkToDeath(deathRecipient, 0)
            }
        }
    }
}
