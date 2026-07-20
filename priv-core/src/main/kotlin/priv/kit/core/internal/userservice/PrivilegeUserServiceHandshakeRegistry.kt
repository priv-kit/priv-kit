package priv.kit.core.internal.userservice

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

internal object PrivilegeUserServiceHandshakeRegistry {
    private val readyProcesses = ConcurrentHashMap<String, IBinder>()

    fun deliverReady(
        token: String?,
        processBinder: IBinder?,
    ): Boolean {
        if (token.isNullOrBlank() || processBinder == null) {
            return false
        }
        readyProcesses[token] = processBinder
        return true
    }

    fun claimReady(token: String?): IBinder? {
        if (token.isNullOrBlank()) {
            return null
        }
        return readyProcesses.remove(token)
    }
}
