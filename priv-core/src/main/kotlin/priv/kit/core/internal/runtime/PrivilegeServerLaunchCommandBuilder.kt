package priv.kit.core.internal.runtime

import priv.kit.core.internal.core.PrivilegeAndroidUsers
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
        ownerUserId: Int = ownerUserId(),
    ): String {
        require(ownerUserId >= 0) { "ownerUserId must not be negative" }
        val ownerUserEnvironment = if (ownerUserId == 0) {
            ""
        } else {
            "${PrivilegeHandshakeContract.ENV_OWNER_USER_ID}=$ownerUserId "
        }
        val launchCorrelationEnvironment = launchCorrelationId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=" +
                    it.toPrivilegeShellArgument() +
                    " "
            }
            .orEmpty()
        return ownerUserEnvironment + launchCorrelationEnvironment + baseNativeStarterCommand
    }

    internal fun buildClasspath(): String {
        val context = PrivilegeContext.require()
        val applicationInfo = context.applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.sorted()?.forEach { add(it) }
        }
        return apkPaths.joinToString(":")
    }

    internal fun ownerUserId(): Int =
        PrivilegeAndroidUsers.userIdFromUid(PrivilegeContext.require().applicationInfo.uid)

    internal const val SERVER_MAIN_CLASS = "priv.kit.core.internal.server.PrivilegeServerMain"
}
