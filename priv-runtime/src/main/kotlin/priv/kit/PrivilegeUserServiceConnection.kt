package priv.kit

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import priv.kit.userservice.PrivilegeUserServiceNotRunningException
import priv.kit.userservice.PrivilegeUserServiceRemoteCallException
import priv.kit.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

public class PrivilegeUserServiceConnection internal constructor(
    public val id: String,
    public val spec: PrivilegeUserServiceSpec,
    public val binder: IBinder,
    private val unbind: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    public fun <T> call(
        operation: String = "call UserService",
        block: (IBinder) -> T,
    ): T =
        try {
            block(binder)
        } catch (exception: DeadObjectException) {
            throw PrivilegeUserServiceNotRunningException(
                "$operation failed because UserService is not running: ${spec.serviceClassName}",
                exception,
            )
        } catch (exception: RemoteException) {
            throw PrivilegeUserServiceRemoteCallException(
                "$operation failed: ${spec.serviceClassName}",
                exception,
            )
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            unbind(id)
        }
    }
}
