package priv.kit.userservice

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

object PrivilegeUserServiceHandshakeRegistry {
    private val readyProcesses = ConcurrentHashMap<String, ReadyProcess>()

    fun deliverReady(
        token: String?,
        processBinder: IBinder?,
        pid: Int,
    ): Boolean {
        if (token.isNullOrBlank() || processBinder == null) {
            return false
        }
        readyProcesses[token] = ReadyProcess(
            processBinder = processBinder,
            pid = pid,
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
        val pid: Int,
    )
}
