package priv.kit.core.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import priv.kit.core.Privilege
import java.io.FileDescriptor

public enum class PrivilegeSystemServiceSource {
    CURRENT_PROCESS,
    SERVER_PROCESS,
}

public abstract class PrivilegeBinderWrapper internal constructor() : IBinder {
    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        val serverBinder = Privilege.requireServerInterface().asBinder()
        val remoteData = Parcel.obtain()
        try {
            remoteData.writeInterfaceToken(DESCRIPTOR)
            writeTargetToRemoteData(remoteData)
            remoteData.writeInt(code)
            remoteData.writeInt(flags)
            remoteData.appendFrom(data, 0, data.dataSize())
            return serverBinder.transact(TRANSACTION_TRANSACT_BINDER, remoteData, reply, 0)
        } finally {
            remoteData.recycle()
        }
    }

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    protected abstract fun writeTargetToRemoteData(data: Parcel)

    protected fun readRemoteInterfaceDescriptor(): String? {
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

    public companion object {
        internal const val DESCRIPTOR: String = "priv.kit.core.binder.IPrivilegeBinderWrapper"
        internal const val TRANSACTION_TRANSACT_BINDER: Int = 0x00FF0001

        internal const val TARGET_BINDER: Int = 1
        internal const val TARGET_SYSTEM_SERVICE: Int = 2

        public fun fromBinder(targetBinder: IBinder): PrivilegeBinderWrapper =
            TargetBinderWrapper(targetBinder)

        public fun fromSystemService(
            serviceName: String,
            source: PrivilegeSystemServiceSource = PrivilegeSystemServiceSource.CURRENT_PROCESS,
        ): PrivilegeBinderWrapper? {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
            return when (source) {
                PrivilegeSystemServiceSource.CURRENT_PROCESS ->
                    ServiceManager.getService(serviceName)?.let(::fromBinder)

                PrivilegeSystemServiceSource.SERVER_PROCESS ->
                    if (hasSystemServiceUnchecked(
                            serviceName = serviceName,
                            source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                        )
                    ) {
                        ServerProcessSystemServiceWrapper(serviceName)
                    } else {
                        null
                    }
            }
        }

        public fun hasSystemService(
            serviceName: String,
            source: PrivilegeSystemServiceSource = PrivilegeSystemServiceSource.CURRENT_PROCESS,
        ): Boolean {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
            return hasSystemServiceUnchecked(serviceName, source)
        }
    }
}

private fun hasSystemServiceUnchecked(
    serviceName: String,
    source: PrivilegeSystemServiceSource,
): Boolean =
    when (source) {
        PrivilegeSystemServiceSource.CURRENT_PROCESS ->
            ServiceManager.getService(serviceName) != null

        PrivilegeSystemServiceSource.SERVER_PROCESS -> {
            Privilege.callServer { server ->
                server.hasSystemService(serviceName)
            }
        }
    }

private class TargetBinderWrapper(
    private val binder: IBinder,
) : PrivilegeBinderWrapper() {
    override fun writeTargetToRemoteData(data: Parcel) {
        data.writeInt(TARGET_BINDER)
        data.writeStrongBinder(binder)
    }

    override fun getInterfaceDescriptor(): String? =
        binder.interfaceDescriptor

    override fun pingBinder(): Boolean =
        binder.pingBinder()

    override fun isBinderAlive(): Boolean =
        binder.isBinderAlive

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        binder.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        binder.dumpAsync(fd, args)
    }

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        binder.linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        binder.unlinkToDeath(recipient, flags)
}

private class ServerProcessSystemServiceWrapper(
    private val serviceName: String,
) : PrivilegeBinderWrapper() {
    override fun writeTargetToRemoteData(data: Parcel) {
        data.writeInt(TARGET_SYSTEM_SERVICE)
        data.writeString(serviceName)
    }

    override fun getInterfaceDescriptor(): String? =
        readRemoteInterfaceDescriptor()

    override fun pingBinder(): Boolean =
        runCatching {
            hasSystemServiceUnchecked(
                serviceName = serviceName,
                source = PrivilegeSystemServiceSource.SERVER_PROCESS,
            )
        }.getOrDefault(false)

    override fun isBinderAlive(): Boolean =
        pingBinder()

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        throw RemoteException(
            "Server-process system service dump is not supported: $serviceName",
        )
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        throw RemoteException(
            "Server-process system service dump is not supported: $serviceName",
        )
    }

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        Privilege.callServer { server ->
            server.asBinder().linkToDeath(recipient, flags)
        }
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        runCatching {
            Privilege.requireServerInterface().asBinder().unlinkToDeath(recipient, flags)
        }.getOrDefault(false)
}
