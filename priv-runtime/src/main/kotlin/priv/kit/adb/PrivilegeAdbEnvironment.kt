package priv.kit.adb

import android.os.SystemProperties

internal object PrivilegeAdbEnvironment {
    fun getActiveAdbTcpPort(): Int {
        val servicePortText = SystemProperties.get(SERVICE_ADB_TCP_PORT, "")
        if (servicePortText.isNotBlank()) {
            return servicePortText.toIntOrNull()?.takeIf(::isValidAdbTcpPort) ?: -1
        }
        return getPersistedAdbTcpPort()
    }

    fun getConfiguredAdbTcpPort(): Int {
        val servicePortText = SystemProperties.get(SERVICE_ADB_TCP_PORT, "")
        val servicePort = servicePortText.toIntOrNull()
        return when {
            servicePortText.isBlank() || servicePort == null || servicePort == -1 -> getPersistedAdbTcpPort()
            isValidAdbTcpPort(servicePort) -> servicePort
            else -> -1
        }
    }

    private fun getPersistedAdbTcpPort(): Int =
        SystemProperties.get(PERSIST_ADB_TCP_PORT, "").toIntOrNull()?.takeIf(::isValidAdbTcpPort) ?: -1

    private fun isValidAdbTcpPort(port: Int): Boolean = port in 1..65535

    private const val SERVICE_ADB_TCP_PORT = "service.adb.tcp.port"
    private const val PERSIST_ADB_TCP_PORT = "persist.adb.tcp.port"
}
