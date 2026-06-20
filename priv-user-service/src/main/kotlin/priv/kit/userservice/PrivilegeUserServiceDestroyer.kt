package priv.kit.userservice

import android.os.IBinder
import android.os.Parcel

internal object PrivilegeUserServiceDestroyer {
    fun destroy(binder: IBinder): Throwable? {
        val result = runCatching {
            val descriptor = binder.interfaceDescriptor ?: return@runCatching
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                val handled = binder.transact(
                    PrivilegeUserServiceTransactions.DESTROY_TRANSACTION_CODE,
                    data,
                    reply,
                    0,
                )
                if (handled && reply.dataSize() > 0) {
                    reply.readException()
                }
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        return result.exceptionOrNull()
    }
}
