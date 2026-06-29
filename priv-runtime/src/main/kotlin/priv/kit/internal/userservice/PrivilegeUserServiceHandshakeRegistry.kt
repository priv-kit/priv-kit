package priv.kit.internal.userservice

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

internal object PrivilegeUserServiceHandshakeRegistry {
    private val readyProcesses = ConcurrentHashMap<String, ReadyProcess>()

    fun deliverReady(
        token: String?,
        processBinder: IBinder?,
    ): Boolean {
        if (token.isNullOrBlank() || processBinder == null) {
            return false
        }
        readyProcesses[token] = ReadyProcess(
            processBinder = processBinder,
        )
        return true
    }

    fun claimReady(token: String?): ReadyProcess? {
        if (token.isNullOrBlank()) {
            return null
        }
        return readyProcesses.remove(token)
    }

    data class ReadyProcess(
        val processBinder: IBinder,
    )
}
