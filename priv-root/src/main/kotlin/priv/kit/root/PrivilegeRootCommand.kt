package priv.kit.root

import priv.kit.core.PrivilegeServerLaunchCommand

data class PrivilegeRootCommand(
    val commandLine: String,
    val classpath: String,
    val mainClass: String,
    val providerAuthority: String,
    val launchCommand: PrivilegeServerLaunchCommand,
)
