package priv.kit.binder

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException
import android.os.ServiceManager
import java.io.FileDescriptor

public enum class PrivilegeSystemServiceSource {
    CURRENT_PROCESS,
    SERVER_PROCESS,
}

public class PrivilegeBinderWrapper private constructor(
    private val target: Target,
) : IBinder {
    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        val serverBinder = PrivilegeBinderRuntime.requireServerBinder()
        val remoteData = Parcel.obtain()
        try {
            remoteData.writeInterfaceToken(DESCRIPTOR)
            target.writeToRemoteData(remoteData)
            remoteData.writeInt(code)
            remoteData.writeInt(flags)
            remoteData.appendFrom(data, 0, data.dataSize())
            return try {
                serverBinder.transact(TRANSACTION_TRANSACT_BINDER, remoteData, reply, 0)
            } catch (exception: DeadObjectException) {
                throw PrivilegeServerDisconnectedException(
                    "Privilege server Binder died while trying to transact Binder",
                    exception,
                )
            } catch (exception: RemoteException) {
                throw PrivilegeBinderRemoteCallException(
                    "Failed to transact Binder through Privileged Server",
                    exception,
                )
            }
        } finally {
            remoteData.recycle()
        }
    }

    override fun getInterfaceDescriptor(): String? =
        target.getInterfaceDescriptor(this)

    override fun pingBinder(): Boolean =
        target.pingBinder(this)

    override fun isBinderAlive(): Boolean =
        target.isBinderAlive(this)

    override fun queryLocalInterface(descriptor: String): IInterface? = null

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        target.dump(fd, args)
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        target.dumpAsync(fd, args)
    }

    @Throws(RemoteException::class)
    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        target.linkToDeath(this, recipient, flags)
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        target.unlinkToDeath(this, recipient, flags)

    private fun readRemoteInterfaceDescriptor(): String? {
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

    private sealed interface Target {
        fun writeToRemoteData(data: Parcel)

        fun getInterfaceDescriptor(owner: PrivilegeBinderWrapper): String?

        fun pingBinder(owner: PrivilegeBinderWrapper): Boolean

        fun isBinderAlive(owner: PrivilegeBinderWrapper): Boolean

        fun dump(fd: FileDescriptor, args: Array<out String>?)

        fun dumpAsync(fd: FileDescriptor, args: Array<out String>?)

        @Throws(RemoteException::class)
        fun linkToDeath(
            owner: PrivilegeBinderWrapper,
            recipient: IBinder.DeathRecipient,
            flags: Int,
        )

        fun unlinkToDeath(
            owner: PrivilegeBinderWrapper,
            recipient: IBinder.DeathRecipient,
            flags: Int,
        ): Boolean

        data class Binder(
            val binder: IBinder,
        ) : Target {
            override fun writeToRemoteData(data: Parcel) {
                data.writeInt(TARGET_BINDER)
                data.writeStrongBinder(binder)
            }

            override fun getInterfaceDescriptor(owner: PrivilegeBinderWrapper): String? =
                binder.interfaceDescriptor

            override fun pingBinder(owner: PrivilegeBinderWrapper): Boolean =
                binder.pingBinder()

            override fun isBinderAlive(owner: PrivilegeBinderWrapper): Boolean =
                binder.isBinderAlive

            override fun dump(fd: FileDescriptor, args: Array<out String>?) {
                binder.dump(fd, args)
            }

            override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
                binder.dumpAsync(fd, args)
            }

            override fun linkToDeath(
                owner: PrivilegeBinderWrapper,
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ) {
                binder.linkToDeath(recipient, flags)
            }

            override fun unlinkToDeath(
                owner: PrivilegeBinderWrapper,
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ): Boolean =
                binder.unlinkToDeath(recipient, flags)
        }

        data class SystemService(
            val serviceName: String,
        ) : Target {
            override fun writeToRemoteData(data: Parcel) {
                data.writeInt(TARGET_SYSTEM_SERVICE)
                data.writeString(serviceName)
            }

            override fun getInterfaceDescriptor(owner: PrivilegeBinderWrapper): String? =
                owner.readRemoteInterfaceDescriptor()

            override fun pingBinder(owner: PrivilegeBinderWrapper): Boolean =
                runCatching {
                    hasSystemService(
                        serviceName = serviceName,
                        source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                    )
                }.getOrDefault(false)

            override fun isBinderAlive(owner: PrivilegeBinderWrapper): Boolean =
                pingBinder(owner)

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
                owner: PrivilegeBinderWrapper,
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ) {
                try {
                    PrivilegeBinderRuntime.requireServerBinder().linkToDeath(recipient, flags)
                } catch (exception: RemoteException) {
                    throw PrivilegeServerDisconnectedException(
                        "Privilege server Binder died while trying to link system service death: $serviceName",
                        exception,
                    )
                }
            }

            override fun unlinkToDeath(
                owner: PrivilegeBinderWrapper,
                recipient: IBinder.DeathRecipient,
                flags: Int,
            ): Boolean =
                runCatching {
                    PrivilegeBinderRuntime.requireServerBinder().unlinkToDeath(recipient, flags)
                }.getOrDefault(false)
        }
    }

    public companion object {
        public const val DESCRIPTOR: String = "priv.kit.binder.IPrivilegeBinderWrapper"
        public const val TRANSACTION_TRANSACT_BINDER: Int = 0x00FF0001

        internal const val TARGET_BINDER: Int = 1
        internal const val TARGET_SYSTEM_SERVICE: Int = 2

        public fun fromBinder(targetBinder: IBinder): PrivilegeBinderWrapper =
            PrivilegeBinderWrapper(Target.Binder(targetBinder))

        public fun fromSystemService(
            serviceName: String,
            source: PrivilegeSystemServiceSource = PrivilegeSystemServiceSource.CURRENT_PROCESS,
        ): PrivilegeBinderWrapper? {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
            return when (source) {
                PrivilegeSystemServiceSource.CURRENT_PROCESS ->
                    ServiceManager.getService(serviceName)?.let(::fromBinder)

                PrivilegeSystemServiceSource.SERVER_PROCESS ->
                    if (hasSystemService(
                            serviceName = serviceName,
                            source = PrivilegeSystemServiceSource.SERVER_PROCESS,
                        )
                    ) {
                        PrivilegeBinderWrapper(Target.SystemService(serviceName))
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
            return when (source) {
                PrivilegeSystemServiceSource.CURRENT_PROCESS ->
                    ServiceManager.getService(serviceName) != null

                PrivilegeSystemServiceSource.SERVER_PROCESS -> {
                    val server = PrivilegeBinderRuntime.requireServer()
                    try {
                        server.hasSystemService(serviceName)
                    } catch (exception: DeadObjectException) {
                        throw PrivilegeServerDisconnectedException(
                            "Privilege server Binder died while trying to resolve system service: $serviceName",
                            exception,
                        )
                    } catch (exception: RemoteException) {
                        throw PrivilegeBinderRemoteCallException(
                            "Failed to resolve system service through Privileged Server: $serviceName",
                            exception,
                        )
                    }
                }
            }
        }

    }
}
