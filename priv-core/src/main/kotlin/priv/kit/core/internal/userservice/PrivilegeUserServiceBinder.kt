package priv.kit.core.internal.userservice

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import priv.kit.core.internal.binder.PrivilegeBinderReply
import java.io.FileDescriptor

/** Restores channel errors before an arbitrary app AIDL proxy reads its own reply. */
internal class PrivilegeUserServiceBinder(
    private val endpoint: IBinder,
    private val state: PrivilegeUserServiceConnectionState,
) : IBinder by endpoint {
    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun isBinderAlive(): Boolean = state.isConnected.value && endpoint.isBinderAlive

    override fun pingBinder(): Boolean = state.isConnected.value && endpoint.pingBinder()

    override fun dump(fd: FileDescriptor, args: Array<out String>?) = dump(fd, args, 0)

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) = dump(fd, args, IBinder.FLAG_ONEWAY)

    private fun dump(fd: FileDescriptor, args: Array<out String>?, flags: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeFileDescriptor(fd)
            data.writeStringArray(args)
            transact(IBinder.DUMP_TRANSACTION, data, reply, flags)
            if (flags == 0) reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = state.call {
        val request = Parcel.obtain()
        try {
            request.writeInterfaceToken(PrivilegeUserServiceGateBinder.DESCRIPTOR)
            request.writeInt(code)
            request.writeInt(flags)
            request.appendFrom(data, 0, data.dataSize())
            PrivilegeBinderReply.transact(
                endpoint, PrivilegeUserServiceGateBinder.TRANSACTION_CALL, request, reply,
            )
        } finally {
            request.recycle()
        }
    }
}
