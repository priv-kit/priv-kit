package priv.kit.core.internal.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import priv.kit.core.binder.PrivilegeUnavailableException

/** An outer reply for our endpoints; the target's reply remains opaque, including its exceptions. */
internal object PrivilegeBinderReply {
    private const val SUCCESS = 0
    private const val UNAVAILABLE = 1
    private const val TARGET_DIED = 2
    private const val REMOTE_ERROR = 3

    fun transact(endpoint: IBinder, code: Int, data: Parcel, reply: Parcel?): Boolean {
        val response = Parcel.obtain()
        try {
            val handled = try {
                endpoint.transact(code, data, response, 0)
            } catch (exception: DeadObjectException) {
                throw PrivilegeUnavailableException(cause = exception)
            }
            if (!handled) return false
            response.readException()
            when (val status = response.readInt()) {
                SUCCESS -> {
                    val targetHandled = response.readInt() != 0
                    reply?.apply {
                        setDataSize(0)
                        setDataPosition(0)
                        appendFrom(response, response.dataPosition(), response.dataAvail())
                        setDataPosition(0)
                    }
                    return targetHandled
                }
                UNAVAILABLE -> throw PrivilegeUnavailableException(response.readString() ?: "Privileged channel is unavailable")
                TARGET_DIED -> throw DeadObjectException(response.readString())
                REMOTE_ERROR -> throw RemoteException(response.readString())
                else -> error("Unknown privileged Binder reply status: $status")
            }
        } finally {
            response.recycle()
        }
    }

    fun dispatch(reply: Parcel?, transaction: (Parcel) -> Boolean): Boolean {
        val response = requireNotNull(reply)
        val targetReply = Parcel.obtain()
        try {
            val handled = transaction(targetReply)
            response.writeNoException()
            response.writeInt(SUCCESS)
            response.writeInt(if (handled) 1 else 0)
            response.appendFrom(targetReply, 0, targetReply.dataSize())
        } catch (exception: PrivilegeUnavailableException) {
            writeFailure(response, UNAVAILABLE, exception)
        } catch (exception: DeadObjectException) {
            writeFailure(response, TARGET_DIED, exception)
        } catch (exception: RemoteException) {
            writeFailure(response, REMOTE_ERROR, exception)
        } finally {
            targetReply.recycle()
        }
        return true
    }

    private fun writeFailure(reply: Parcel, status: Int, exception: Exception) {
        reply.setDataSize(0)
        reply.setDataPosition(0)
        reply.writeNoException()
        reply.writeInt(status)
        reply.writeString(exception.message)
    }
}
