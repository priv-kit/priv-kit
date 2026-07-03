package priv.kit.adb

import android.os.SystemProperties

internal object PrivilegeAdbEnvironment {
    fun getAdbTcpPort(): Int {
        val servicePortText = SystemProperties.get("service.adb.tcp.port", "")
        if (servicePortText.isNotBlank()) {
            return servicePortText.toIntOrNull()?.takeIf { it > 0 } ?: -1
        }
        val persistPort = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (persistPort > 0) return persistPort
        return -1
    }
}
