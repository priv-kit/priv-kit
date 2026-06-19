package priv.kit.core

data class PrivilegeServerLaunchCommand(
    val token: String,
    val foregroundCommandLine: String,
    val detachedCommandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val packageName: String,
    val mode: PrivilegeMode,
    val protocolVersion: Int,
    val serverVersion: String,
)
