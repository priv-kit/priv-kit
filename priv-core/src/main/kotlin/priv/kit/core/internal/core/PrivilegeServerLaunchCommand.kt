package priv.kit.core.internal.core

internal data class PrivilegeServerLaunchCommand(
    val commandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
)
