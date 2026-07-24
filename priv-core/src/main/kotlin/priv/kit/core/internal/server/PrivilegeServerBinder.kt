package priv.kit.core.internal.server

import android.content.pm.IPackageManager
import android.os.IBinder
import android.os.Parcel
import android.os.Process as AndroidProcess
import android.os.ServiceManager
import android.util.Log
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.userservice.PrivilegeUserServiceLoader
import priv.kit.core.internal.userservice.PrivilegeUserServiceManagerBinder
import priv.kit.core.internal.userservice.PrivilegeUserServiceRegistry
import kotlin.system.exitProcess

internal class PrivilegeServerBinder(
    config: PrivilegeServerConfig,
    private val onShutdown: () -> Unit = {},
) : IPrivilegeServer.Stub() {
    private val packageContextRuntime by lazy {
        val context = PrivilegeUserServiceLoader.createPackageContext(
            packageName = config.packageName,
            userId = config.userId,
        )
        PrivilegeUserServiceLoader.ContextRuntime(
            context = context,
            classLoader = context.classLoader,
        )
    }
    private val userServiceManager = PrivilegeUserServiceManagerBinder(
        PrivilegeUserServiceRegistry(
            host = PrivilegeServerUserServiceHost(config),
            embeddedContextRuntimeProvider = { packageContextRuntime },
        ),
    )
    private val packageManager by lazy {
        IPackageManager.Stub.asInterface(getSystemService("package"))
    }
    private val systemServiceCache = HashMap<String, IBinder>()

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

    override fun hasSystemService(serviceName: String): Boolean {
        return getSystemService(serviceName) != null
    }

    override fun checkServerPermission(permission: String): Int {
        return packageContextRuntime.context.checkPermission(
            permission,
            AndroidProcess.myPid(),
            AndroidProcess.myUid(),
        )
    }

    override fun checkPermission(
        permName: String,
        pkgName: String,
        userId: Int,
    ): Int = packageManager.checkPermission(permName, pkgName, userId)

    override fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
    ) {
        packageManager.grantRuntimePermission(packageName, permissionName, userId)
    }

    override fun shutdown() {
        Log.i(TAG, "Shutdown requested by client")
        userServiceManager.destroyAll()
        Thread {
            Thread.sleep(SHUTDOWN_DELAY_MILLIS)
            onShutdown()
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
            PrivilegeBinderWrapper.TARGET_BINDER -> data.readStrongBinder()
            PrivilegeBinderWrapper.TARGET_SYSTEM_SERVICE -> resolveSystemService(
                data.readString(),
                reply
            ) ?: return

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
            getSystemService(serviceName)
        } catch (exception: RuntimeException) {
            writeRemoteBinderException(
                reply = reply,
                exception = exception,
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

    @Synchronized
    private fun getSystemService(serviceName: String): IBinder? =
        systemServiceCache[serviceName] ?: ServiceManager.getService(serviceName)?.also {
            systemServiceCache[serviceName] = it
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
    }
}
