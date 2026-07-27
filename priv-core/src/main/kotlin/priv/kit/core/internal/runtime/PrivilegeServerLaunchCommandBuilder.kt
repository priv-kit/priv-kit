package priv.kit.core.internal.runtime

import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeServerLaunchCommand
import priv.kit.shared.toPrivilegeShellArgument

internal object PrivilegeServerLaunchCommandBuilder {
    fun build(starterCommandLine: String): PrivilegeServerLaunchCommand {
        val context = PrivilegeContext.require()
        val packageName = context.packageName
        val classpath = buildClasspath()
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)

        return PrivilegeServerLaunchCommand(
            commandLine = starterCommandLine,
            classpath = classpath,
            mainClass = SERVER_MAIN_CLASS,
            providerAuthority = providerAuthority,
        )
    }

    internal fun resolveNativeStarterCommand(): String =
        PrivilegeNativeStarterResolver.commandLine(PrivilegeNativeStarterResolver.resolve())

    internal fun buildNativeStarterCommand(
        baseNativeStarterCommand: String,
        launchCorrelationId: String?,
    ): String =
        "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=" +
            launchCorrelationId.orEmpty().toPrivilegeShellArgument() +
            " " +
            baseNativeStarterCommand

    internal fun buildClasspath(): String {
        val context = PrivilegeContext.require()
        val applicationInfo = context.applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.sorted()?.forEach { add(it) }
        }
        return apkPaths.joinToString(":")
    }

    internal const val SERVER_MAIN_CLASS = "priv.kit.core.internal.server.PrivilegeServerMain"
}
