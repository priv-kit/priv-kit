package priv.kit.internal.core

internal data class PrivilegeServerLaunchCommand(
    val commandLine: String,
    val classpath: String,
    val classpathIdentity: String,
    val mainClass: String,
    val providerAuthority: String,
    val packageName: String,
    val protocolVersion: Int,
    val userId: Int = 0,
)
