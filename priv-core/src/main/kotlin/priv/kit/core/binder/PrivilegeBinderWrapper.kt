package priv.kit.core.binder

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.ResultReceiver
import android.os.ServiceManager
import android.os.ShellCallback
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeUserServiceConnection
import priv.kit.core.internal.binder.PrivilegeBinderForwarder
import priv.kit.shared.transactShellCommand
import java.io.FileDescriptor

public enum class PrivilegeSystemServiceSource {
    CURRENT_PROCESS,
    REMOTE_PROCESS,
}

public abstract class PrivilegeBinderWrapper internal constructor(
    protected val userService: PrivilegeUserServiceConnection? = null,
) : IBinder {
    protected fun forwardingBinder(): IBinder =
        userService?.requireForwardingBinder() ?: Privilege.requireServerInterface().asBinder()

    override fun transact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        val serverBinder = forwardingBinder()
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

    override fun dump(fd: FileDescriptor, args: Array<out String>?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeFileDescriptor(fd)
            data.writeStringArray(args)
            transact(IBinder.DUMP_TRANSACTION, data, reply, 0)
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun dumpAsync(fd: FileDescriptor, args: Array<out String>?) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeFileDescriptor(fd)
            data.writeStringArray(args)
            transact(IBinder.DUMP_TRANSACTION, data, reply, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    public fun shellCommand(
        input: FileDescriptor?,
        output: FileDescriptor?,
        error: FileDescriptor?,
        args: Array<out String>,
        shellCallback: ShellCallback?,
        resultReceiver: ResultReceiver,
    ) {
        val reply = Parcel.obtain()
        try {
            transactShellCommand(input, output, error, args, shellCallback, resultReceiver, reply)
            reply.readException()
        } finally {
            reply.recycle()
        }
    }

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

        public fun fromBinder(
            targetBinder: IBinder,
            userService: PrivilegeUserServiceConnection? = null,
        ): PrivilegeBinderWrapper = TargetBinderWrapper(targetBinder, userService)

        public fun fromSystemService(
            serviceName: String,
            source: PrivilegeSystemServiceSource = PrivilegeSystemServiceSource.REMOTE_PROCESS,
            userService: PrivilegeUserServiceConnection? = null,
        ): PrivilegeBinderWrapper? {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
            return when (source) {
                PrivilegeSystemServiceSource.CURRENT_PROCESS ->
                    getCurrentProcessSystemService(serviceName)?.let { fromBinder(it, userService) }

                PrivilegeSystemServiceSource.REMOTE_PROCESS ->
                    if (hasSystemServiceUnchecked(
                            serviceName = serviceName,
                            source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
                            userService = userService,
                        )
                    ) {
                        RemoteProcessSystemServiceWrapper(serviceName, userService)
                    } else {
                        null
                    }
            }
        }

        public fun hasSystemService(
            serviceName: String,
            source: PrivilegeSystemServiceSource = PrivilegeSystemServiceSource.REMOTE_PROCESS,
            userService: PrivilegeUserServiceConnection? = null,
        ): Boolean {
            require(serviceName.isNotBlank()) { "serviceName must not be blank" }
            return hasSystemServiceUnchecked(serviceName, source, userService)
        }
    }
}

private fun hasSystemServiceUnchecked(
    serviceName: String,
    source: PrivilegeSystemServiceSource,
    userService: PrivilegeUserServiceConnection? = null,
): Boolean =
    when (source) {
        PrivilegeSystemServiceSource.CURRENT_PROCESS ->
            getCurrentProcessSystemService(serviceName) != null

        PrivilegeSystemServiceSource.REMOTE_PROCESS -> {
            if (userService != null) {
                PrivilegeBinderForwarder.hasSystemService(userService.requireForwardingBinder(), serviceName)
            } else {
                Privilege.callServer { server -> server.hasSystemService(serviceName) }
            }
        }
    }

private val currentProcessSystemServiceCache = HashMap<String, IBinder>()

@Synchronized
private fun getCurrentProcessSystemService(serviceName: String): IBinder? =
    currentProcessSystemServiceCache[serviceName]
        ?: ServiceManager.getService(serviceName)?.also { binder ->
            currentProcessSystemServiceCache[serviceName] = binder
        }

private class TargetBinderWrapper(
    private val binder: IBinder,
    userService: PrivilegeUserServiceConnection?,
) : PrivilegeBinderWrapper(userService) {
    override fun writeTargetToRemoteData(data: Parcel) {
        data.writeInt(TARGET_BINDER)
        data.writeStrongBinder(binder)
    }

    override fun getInterfaceDescriptor(): String? =
        binder.interfaceDescriptor

    override fun pingBinder(): Boolean =
        binder.pingBinder() && (userService?.isForwardingAlive() ?: true)

    override fun isBinderAlive(): Boolean =
        binder.isBinderAlive && (userService?.isForwardingAlive() ?: true)

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        userService?.requireForwardingBinder()
        (userService?.lifecycleBinder ?: binder).linkToDeath(recipient, flags)
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        (userService?.lifecycleBinder ?: binder).unlinkToDeath(recipient, flags)
}

private class RemoteProcessSystemServiceWrapper(
    private val serviceName: String,
    userService: PrivilegeUserServiceConnection?,
) : PrivilegeBinderWrapper(userService) {
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
                source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
                userService = userService,
            )
        }.getOrDefault(false)

    override fun isBinderAlive(): Boolean =
        pingBinder()

    override fun linkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ) {
        if (userService != null) {
            userService.requireForwardingBinder()
            userService.lifecycleBinder.linkToDeath(recipient, flags)
        } else {
            Privilege.callServer { server -> server.asBinder().linkToDeath(recipient, flags) }
        }
    }

    override fun unlinkToDeath(
        recipient: IBinder.DeathRecipient,
        flags: Int,
    ): Boolean =
        runCatching {
            (userService?.lifecycleBinder ?: Privilege.requireServerInterface().asBinder()).unlinkToDeath(recipient, flags)
        }.getOrDefault(false)
}
