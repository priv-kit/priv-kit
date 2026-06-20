package priv.kit.adb

import android.os.SystemProperties

internal object PrivilegeAdbEnvironment {
    fun getAdbTcpPort(): Int {
        val servicePort = getSystemPropertyInt("service.adb.tcp.port", -1)
        if (servicePort > 0) return servicePort
        val persistPort = getSystemPropertyInt("persist.adb.tcp.port", -1)
        if (persistPort > 0) return persistPort
        return -1
    }

    private fun getSystemPropertyInt(name: String, defaultValue: Int): Int =
        SystemProperties.getInt(name, defaultValue)
}
