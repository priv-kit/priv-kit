package priv.kit.core

data class PrivilegeServerInfo(
    val uid: Int,
    val pid: Int,
    val mode: Int,
    val protocolVersion: Int,
    val serverVersion: String,
)
