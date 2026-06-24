package priv.kit.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderRegistry
import priv.kit.binder.PrivilegeRemoteBinderWrapper
import priv.kit.binder.PrivilegeRemoteSystemServiceBinder
import priv.kit.userservice.PrivilegeUserServiceManagerBinder
import priv.kit.userservice.PrivilegeUserServiceRegistry
import kotlin.system.exitProcess

internal class PrivilegeServerBinder(
    private val config: PrivilegeServerConfig,
) : IPrivilegeServer.Stub() {
    private val binderRegistry = PrivilegeBinderRegistry()
    private val userServiceManager = PrivilegeUserServiceManagerBinder(
        PrivilegeUserServiceRegistry(
            host = PrivilegeServerUserServiceHost(config),
        ),
    )

    override fun registerBinderEndpoint(binder: IBinder) {
        binderRegistry.register(binder)
    }

    override fun getBinderEndpoint(): IBinder? =
        binderRegistry.getBinder()

    override fun unregisterBinderEndpoint(): Boolean =
        binderRegistry.unregister()

    override fun getUserServiceManager(): IBinder =
        userServiceManager.asBinder()

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code == PrivilegeRemoteBinderWrapper.TRANSACTION_TRANSACT_REMOTE) {
            data.enforceInterface(PrivilegeRemoteBinderWrapper.DESCRIPTOR)
            transactRemote(data, reply)
            return true
        }
        if (code == PrivilegeRemoteSystemServiceBinder.TRANSACTION_TRANSACT_SYSTEM_SERVICE) {
            data.enforceInterface(PrivilegeRemoteSystemServiceBinder.DESCRIPTOR)
            transactSystemService(data, reply)
            return true
        }
        return super.onTransact(code, data, reply, flags)
    }

    override fun shutdown() {
        Log.i(TAG, "Shutdown requested by client")
        binderRegistry.clear()
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
        val targetBinder = data.readStrongBinder()
        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        val targetData = Parcel.obtain()
        try {
            targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
            val identity = Binder.clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, targetData, reply, targetFlags)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        } finally {
            targetData.recycle()
        }
    }

    private fun transactSystemService(
        data: Parcel,
        reply: Parcel?,
    ) {
        val serviceName = data.readString()
        if (serviceName.isNullOrBlank()) {
            writeSystemServiceException(
                reply = reply,
                exception = IllegalArgumentException("System service name must not be blank"),
            )
            return
        }

        val targetCode = data.readInt()
        val targetFlags = data.readInt()
        val targetBinder = try {
            ServiceManager.getService(serviceName)
        } catch (throwable: Throwable) {
            writeSystemServiceException(
                reply = reply,
                exception = IllegalStateException(
                    "Failed to get system service: $serviceName",
                    throwable,
                ),
            )
            return
        }

        if (targetBinder == null) {
            writeSystemServiceException(
                reply = reply,
                exception = IllegalStateException("System service not found: $serviceName"),
            )
            return
        }

        val targetData = Parcel.obtain()
        try {
            targetData.appendFrom(data, data.dataPosition(), data.dataAvail())
            val identity = Binder.clearCallingIdentity()
            try {
                targetBinder.transact(targetCode, targetData, reply, targetFlags)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        } finally {
            targetData.recycle()
        }
    }

    private fun writeSystemServiceException(
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
    }
}
