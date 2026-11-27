package priv.kit.core.internal.userservice

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.internal.binder.PrivilegeBinderReply
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal class PrivilegeUserServiceGateBinder(
    target: IBinder? = null,
    private val forwarder: PrivilegeBinderForwarder = PrivilegeBinderForwarder(),
) : Binder(),
    Closeable {
    private val target = target ?: forwarder
    private val closed = AtomicBoolean(false)

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code == IBinder.INTERFACE_TRANSACTION) {
            reply?.writeString(target.interfaceDescriptor)
            return true
        }
        val forwarding = forwarder.handles(code)
        if (!forwarding && code != TRANSACTION_CALL) return super.onTransact(code, data, reply, flags)
        return PrivilegeBinderReply.dispatch(reply) { targetReply ->
            if (closed.get()) throw PrivilegeUnavailableException("Privilege connection is closed")
            if (forwarding) return@dispatch forwarder.dispatchTarget(code, data, targetReply)
            data.enforceInterface(DESCRIPTOR)
            val targetCode = data.readInt()
            val targetFlags = data.readInt()
            val targetData = Parcel.obtain()
            try {
                targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
                targetData.setDataPosition(0)
                target.transact(targetCode, targetData, targetReply, targetFlags)
            } finally {
                targetData.recycle()
            }
        }
    }

    override fun close() {
        closed.set(true)
    }

    companion object {
        const val DESCRIPTOR = "priv.kit.core.internal.userservice.IPrivilegeUserServiceGate"
        const val TRANSACTION_CALL = IBinder.FIRST_CALL_TRANSACTION
    }
}
