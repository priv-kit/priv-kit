package priv.kit.delegate

import priv.kit.core.PrivilegeServerLaunchCommand

data class PrivilegeDelegateCommand(
    val foregroundCommandLine: String,
    val detachedCommandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val launchCommand: PrivilegeServerLaunchCommand,
)
