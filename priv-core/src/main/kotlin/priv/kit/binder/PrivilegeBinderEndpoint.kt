package priv.kit.binder

import android.os.IBinder
import android.os.RemoteException
import java.io.Closeable

public class PrivilegeBinderEndpoint public constructor(
    private val binder: IBinder,
) {
    public val isAlive: Boolean
        get() = binder.pingBinder()

    public fun asBinder(): IBinder = binder

    public fun watchDeath(onDied: (PrivilegeBinderEndpoint) -> Unit): Closeable {
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
