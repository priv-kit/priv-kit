package priv.kit.userservice

import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap

public object PrivilegeUserServiceHandshakeRegistry {
    private val readyProcesses = ConcurrentHashMap<String, ReadyProcess>()

    public fun deliverReady(
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

    public fun claimReady(token: String?): ReadyProcess? {
        if (token.isNullOrBlank()) {
            return null
        }
        return readyProcesses.remove(token)
    }

    public data class ReadyProcess public constructor(
        public val processBinder: IBinder,
        public val pid: Int,
    )
}
