package priv.kit.adb

import android.os.SystemProperties
import priv.kit.shared.isPrivilegeAdbPort

internal object PrivilegeAdbEnvironment {
    fun getActiveAdbTcpPort(): Int {
        val servicePortText = SystemProperties.get(SERVICE_ADB_TCP_PORT, "")
        if (servicePortText.isNotBlank()) {
            return servicePortText.toIntOrNull()?.takeIf { it.isPrivilegeAdbPort() } ?: -1
        }
        return getPersistedAdbTcpPort()
    }

    fun getConfiguredAdbTcpPort(): Int {
        val servicePortText = SystemProperties.get(SERVICE_ADB_TCP_PORT, "")
        val servicePort = servicePortText.toIntOrNull()
        return when {
            servicePortText.isBlank() || servicePort == null || servicePort == -1 -> getPersistedAdbTcpPort()
            servicePort.isPrivilegeAdbPort() -> servicePort
            else -> -1
        }
    }

    private fun getPersistedAdbTcpPort(): Int =
        SystemProperties.get(PERSIST_ADB_TCP_PORT, "").toIntOrNull()?.takeIf { it.isPrivilegeAdbPort() } ?: -1

    private const val SERVICE_ADB_TCP_PORT = "service.adb.tcp.port"
    private const val PERSIST_ADB_TCP_PORT = "persist.adb.tcp.port"
}
