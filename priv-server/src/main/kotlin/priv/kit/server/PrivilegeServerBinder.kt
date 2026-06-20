package priv.kit.server

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.util.Log
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderRegistry
import priv.kit.binder.PrivilegeRemoteBinderWrapper
import kotlin.system.exitProcess

internal class PrivilegeServerBinder(
    private val config: PrivilegeServerConfig,
) : IPrivilegeServer.Stub() {
    private val binderRegistry = PrivilegeBinderRegistry()

    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    override fun getLaunchMode(): Int = config.launchMode

    override fun getProtocolVersion(): Int = config.protocolVersion

    override fun getServerVersion(): String = config.serverVersion

    override fun updateOwnerDeathConfig(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        PrivilegeServerMain.updateOwnerDeathConfig(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
    }

    override fun registerBinderEndpoint(binder: IBinder) {
        binderRegistry.register(binder)
    }

    override fun getBinderEndpoint(): IBinder? =
        binderRegistry.getBinder()

    override fun unregisterBinderEndpoint(): Boolean =
        binderRegistry.unregister()

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
        return super.onTransact(code, data, reply, flags)
    }

    override fun shutdown() {
        Log.i(TAG, "Shutdown requested by client")
        binderRegistry.clear()
        Thread {
            Thread.sleep(SHUTDOWN_DELAY_MILLIS)
            exitProcess(0)
        }.start()
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

    companion object {
        private const val TAG = "PrivKitServer"
        private const val SHUTDOWN_DELAY_MILLIS = 50L
    }
}
