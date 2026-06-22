package priv.kit.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import java.io.FileDescriptor

/**
 * Raw Binder bridge for an explicitly named Android system service.
 *
 * The client process never calls ServiceManager for [serviceName]. Each transaction is sent to
 * the current Privileged Server, which resolves the service name from its own SELinux domain and
 * forwards only the original Binder transaction.
 */
class PrivilegeRemoteSystemServiceBinder(
    private val serviceName: String,
) : IBinder {
    init {
        require(serviceName.isNotBlank()) { "serviceName must not be blank" }
    }

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
            remoteData.writeString(serviceName)
            remoteData.writeInt(code)
            remoteData.writeInt(flags)
            remoteData.appendFrom(data, 0, data.dataSize())
            return try {
                serverBinder.transact(TRANSACTION_TRANSACT_SYSTEM_SERVICE, remoteData, reply, 0)
            } catch (exception: DeadObjectException) {
                throw PrivilegeServerDisconnectedException(
                    "Privilege server Binder died while trying to transact system service: $serviceName",
                    exception,
                )
            } catch (exception: RemoteException) {
                throw PrivilegeBinderRemoteCallException(
                    "Failed to transact system service through Privileged Server: $serviceName",
                    exception,
                )
            }
        } finally {
            remoteData.recycle()
        }
    }

    override fun getInterfaceDescriptor(): String? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            if (!transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0)) {
                null
            } else {
                reply.readString()
            }
        } catch (_: Throwable) {
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun pingBinder(): Boolean =
        runCatching {
            requireServerBinder().pingBinder()
        }.getOrDefault(false)

    override fun isBinderAlive(): Boolean =
        pingBinder()

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    @Throws(RemoteException::class)
    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        throw RemoteException("Remote system service dump is not supported: $serviceName")
    }

    @Throws(RemoteException::class)
    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        throw RemoteException("Remote system service dump is not supported: $serviceName")
    }

    @Throws(RemoteException::class)
    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        requireServerBinder().linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        runCatching {
            requireServerBinder().unlinkToDeath(recipient, flags)
        }.getOrDefault(false)

    internal fun requireServerBinder(): IBinder =
        PrivilegeBinderRuntime.requireServerBinder()

    companion object {
        const val DESCRIPTOR = "priv.kit.binder.IPrivilegeRemoteSystemServiceBinder"
        const val TRANSACTION_TRANSACT_SYSTEM_SERVICE = 0x00FF0002
    }
}
