package priv.kit.runtime

import priv.kit.core.PrivilegeServerLaunchCommand

data class PrivilegeManualShellCommand(
    val token: String,
    val commandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val launchCommand: PrivilegeServerLaunchCommand,
)
