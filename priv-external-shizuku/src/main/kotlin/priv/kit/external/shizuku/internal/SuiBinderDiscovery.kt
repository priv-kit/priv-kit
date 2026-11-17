package priv.kit.external.shizuku.internal

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log

internal object SuiBinderDiscovery {
    private const val BRIDGE_TRANSACTION = 0x5f535549 // _SUI
    private const val GET_BINDER = 2

    fun findBinder(): IBinder? {
        val activityService = ServiceManager.getService(Context.ACTIVITY_SERVICE) ?: return null
        return try {
            requestBinder(activityService)
        } catch (exception: Exception) {
            Log.d("PrivilegeExternalShizuku", "Sui Binder discovery is unavailable", exception)
            null
        }
    }

    private fun requestBinder(activityService: IBinder): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.app.IActivityManager")
            data.writeInt(GET_BINDER)
            if (!activityService.transact(BRIDGE_TRANSACTION, data, reply, 0)) return null
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
