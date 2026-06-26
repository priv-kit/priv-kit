package priv.kit.server

import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderWrapper
import priv.kit.userservice.PrivilegeUserServiceManagerBinder
import priv.kit.userservice.PrivilegeUserServiceRegistry
import kotlin.system.exitProcess

internal class PrivilegeServerBinder(
    config: PrivilegeServerConfig,
) : IPrivilegeServer.Stub() {
    private val userServiceManager = PrivilegeUserServiceManagerBinder(
        PrivilegeUserServiceRegistry(
            host = PrivilegeServerUserServiceHost(config),
        ),
    )

    override fun getUserServiceManager(): IBinder =
        userServiceManager.asBinder()

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code == PrivilegeBinderWrapper.TRANSACTION_TRANSACT_BINDER) {
            data.enforceInterface(PrivilegeBinderWrapper.DESCRIPTOR)
            transactRemote(data, reply)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun hasSystemService(serviceName: String?): Boolean {
        if (serviceName.isNullOrBlank()) {
            throw IllegalArgumentException("System service name must not be blank")
        }

        return try {
            ServiceManager.getService(serviceName) != null
        } catch (throwable: Throwable) {
            throw IllegalStateException(
                "Failed to get system service: $serviceName",
                throwable,
            )
        }
    }

    override fun shutdown() {
        Log.i(TAG, "Shutdown requested by client")
        userServiceManager.destroyAll()
        Thread {
            Thread.sleep(SHUTDOWN_DELAY_MILLIS)
            exitProcess(0)
        }.start()
    }

    fun destroyUserServicesOnOwnerDeath() {
        userServiceManager.destroyOnOwnerDeath()
    }

    private fun transactRemote(
        data: Parcel,
        reply: Parcel?,
    ) {
        val targetBinder = when (val targetKind = data.readInt()) {
            TARGET_BINDER -> data.readStrongBinder()
            TARGET_SYSTEM_SERVICE -> resolveSystemService(data.readString(), reply) ?: return
            else -> error("Unknown remote Binder target kind: $targetKind")
        }
        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        val targetData = Parcel.obtain()
        try {
            targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
            val identity = clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, targetData, reply, targetFlags)
            } finally {
                restoreCallingIdentity(identity)
            }
        } finally {
            targetData.recycle()
        }
    }

    private fun resolveSystemService(
        serviceName: String?,
        reply: Parcel?,
    ): IBinder? {
        if (serviceName.isNullOrBlank()) {
            writeRemoteBinderException(
                reply = reply,
                exception = IllegalArgumentException("System service name must not be blank"),
            )
            return null
        }

        val targetBinder = try {
            ServiceManager.getService(serviceName)
        } catch (throwable: Throwable) {
            writeRemoteBinderException(
                reply = reply,
                exception = IllegalStateException(
                    "Failed to get system service: $serviceName",
                    throwable,
                ),
            )
            return null
        }

        if (targetBinder == null) {
            writeRemoteBinderException(
                reply = reply,
                exception = IllegalStateException("System service not found: $serviceName"),
            )
        }
        return targetBinder
    }

    private fun writeRemoteBinderException(
        reply: Parcel?,
        exception: RuntimeException,
    ) {
        if (reply != null) {
            reply.writeException(exception)
        } else {
            throw exception
        }
    }

    companion object {
        private const val TAG = "PrivKitServer"
        private const val SHUTDOWN_DELAY_MILLIS = 50L
        private const val TARGET_BINDER = 1
        private const val TARGET_SYSTEM_SERVICE = 2
    }
}
