package priv.kit.adb

import android.os.SystemProperties

internal object PrivilegeAdbEnvironment {
    fun getAdbTcpPort(): Int {
        val servicePort = SystemProperties.getInt("service.adb.tcp.port", -1)
        if (servicePort > 0) return servicePort
        val persistPort = SystemProperties.getInt("persist.adb.tcp.port", -1)
        if (persistPort > 0) return persistPort
        return -1
    }
}
