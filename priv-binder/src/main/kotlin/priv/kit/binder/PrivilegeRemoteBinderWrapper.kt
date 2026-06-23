package priv.kit.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor

public class PrivilegeRemoteBinderWrapper public constructor(
    private val targetBinder: IBinder,
) : IBinder {
    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        val serverBinder = requireServerBinder()
        val remoteData = Parcel.obtain()
        try {
            remoteData.writeInterfaceToken(DESCRIPTOR)
            remoteData.writeStrongBinder(targetBinder)
            remoteData.writeInt(code)
            remoteData.writeInt(flags)
            remoteData.appendFrom(data, 0, data.dataSize())
            return try {
                serverBinder.transact(TRANSACTION_TRANSACT_REMOTE, remoteData, reply, 0)
            } catch (exception: DeadObjectException) {
                throw PrivilegeServerDisconnectedException(
                    "Privilege server Binder died while trying to transact remote Binder",
                    exception,
                )
            } catch (exception: RemoteException) {
                throw PrivilegeBinderRemoteCallException(
                    "Failed to transact remote Binder through Privileged Server",
                    exception,
                )
            }
        } finally {
            remoteData.recycle()
        }
    }

    override fun getInterfaceDescriptor(): String? =
        targetBinder.interfaceDescriptor

    override fun pingBinder(): Boolean =
        targetBinder.pingBinder()

    override fun isBinderAlive(): Boolean =
        targetBinder.isBinderAlive

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        targetBinder.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        targetBinder.dumpAsync(fd, args)
    }

    @Throws(RemoteException::class)
    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        targetBinder.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        targetBinder.unlinkToDeath(recipient, flags)

    internal fun requireServerBinder(): IBinder =
        PrivilegeBinderRuntime.requireServerBinder()

    public companion object {
        public const val DESCRIPTOR: String = "priv.kit.binder.IPrivilegeRemoteBinder"
        public const val TRANSACTION_TRANSACT_REMOTE: Int = 0x00FF0001
    }
}
