package priv.kit.sample

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.annotation.Keep

/** Raw target Binder lets the device test kill the target independently of the forwarding channel. */
@Keep
class UserServiceFailureProbe : Binder() {
    private val target = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            handle(code, data, reply, flags)
    }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        handle(code, data, reply, flags)

    private fun handle(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        when (code) {
            GET_TARGET -> {
                reply!!.writeNoException()
                reply.writeStrongBinder(target)
            }
            GET_PID -> {
                reply!!.writeNoException()
                reply.writeInt(Process.myPid())
            }
            GET_CALLER_PID -> {
                reply!!.writeNoException()
                reply.writeInt(getCallingPid())
            }
            CALL_DEPENDENCY -> {
                val dependency = checkNotNull(data.readStrongBinder())
                val request = Parcel.obtain()
                try {
                    check(dependency.transact(GET_PID, request, reply, 0))
                } finally {
                    request.recycle()
                }
            }
            STATE_ERROR -> throw IllegalStateException("probe business state")
            SECURITY_ERROR -> throw SecurityException("probe permission denied")
            ECHO -> {
                reply!!.writeNoException()
                reply.writeString(data.readString())
                reply.writeStrongBinder(data.readStrongBinder())
                data.readFileDescriptor().use { reply.writeFileDescriptor(it.fileDescriptor) }
            }
            16777115 -> reply?.writeNoException() // Runtime destroy transaction (AIDL id + FIRST_CALL_TRANSACTION).
            else -> return super.onTransact(code, data, reply, flags)
        }
        return true
    }

    companion object {
        const val GET_TARGET = IBinder.FIRST_CALL_TRANSACTION
        const val GET_PID = GET_TARGET + 1
        const val STATE_ERROR = GET_TARGET + 2
        const val SECURITY_ERROR = GET_TARGET + 3
        const val ECHO = GET_TARGET + 4
        const val GET_CALLER_PID = GET_TARGET + 5
        const val CALL_DEPENDENCY = GET_TARGET + 6
    }
}
