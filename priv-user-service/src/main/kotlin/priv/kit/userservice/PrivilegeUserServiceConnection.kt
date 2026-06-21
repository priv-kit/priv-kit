package priv.kit.userservice

import android.os.DeadObjectException
import android.os.IBinder
import android.os.RemoteException
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class PrivilegeUserServiceConnection internal constructor(
    val id: String,
    val spec: PrivilegeUserServiceSpec,
    val binder: IBinder,
    val status: PrivilegeUserServiceStatus,
    private val unbind: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    fun <T> requireInterface(
        operation: String = "resolve UserService interface",
        factory: (IBinder) -> T?,
    ): T =
        factory(binder) ?: throw PrivilegeUserServiceNotRunningException(
            "$operation returned an invalid UserService Binder: ${spec.serviceClassName}",
        )

    fun <T> call(
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
