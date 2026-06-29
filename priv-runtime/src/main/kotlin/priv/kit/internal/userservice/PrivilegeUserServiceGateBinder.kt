package priv.kit.internal.userservice

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceGateBinder(
    private val target: IBinder,
) : Binder(),
    Closeable {
    private val closed = AtomicBoolean(false)

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (closed.get()) {
            throw DeadObjectException("Privilege UserService was destroyed")
        }
        return target.transact(code, data, reply, flags)
    }

    override fun close() {
        closed.set(true)
    }
}
