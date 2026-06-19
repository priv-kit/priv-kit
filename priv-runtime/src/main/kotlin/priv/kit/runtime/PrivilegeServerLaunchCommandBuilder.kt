package priv.kit.runtime

import android.content.Context
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerLaunchCommand

internal object PrivilegeServerLaunchCommandBuilder {
    fun build(
        context: Context,
        token: String,
        mode: PrivilegeMode,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeServerLaunchCommand {
        val packageName = context.packageName
        val classpath = buildClasspath(context)
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)
        val processName = buildServerProcessName(packageName)
        val foregroundCommandLine = buildString {
            append("CLASSPATH=")
            append(shellArg(classpath))
            append(" /system/bin/app_process /system/bin")
            append(" --nice-name=")
            append(shellArg(processName))
            append(' ')
            append(shellArg(SERVER_MAIN_CLASS))
            append(" --token ")
            append(shellArg(token))
            append(" --provider-authority ")
            append(shellArg(providerAuthority))
            append(" --package-name ")
            append(shellArg(packageName))
            append(" --mode ")
            append(mode.value)
            append(" --protocol-version ")
            append(PrivilegeProtocol.VERSION)
            append(" --server-version ")
            append(shellArg(PrivilegeProtocol.SERVER_VERSION))
            append(" --follow-death-delay-millis ")
            append(followDeathDelayMillis)
            append(" --active-reconnect-on-owner-death ")
            append(activeReconnectOnOwnerDeath)
        }

        return PrivilegeServerLaunchCommand(
            token = token,
            foregroundCommandLine = foregroundCommandLine,
            detachedCommandLine = "($foregroundCommandLine </dev/null >/dev/null 2>&1 &)",
            classpath = classpath,
            mainClass = SERVER_MAIN_CLASS,
            providerAuthority = providerAuthority,
            packageName = packageName,
            mode = mode,
            protocolVersion = PrivilegeProtocol.VERSION,
            serverVersion = PrivilegeProtocol.SERVER_VERSION,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
    }

    fun buildServerProcessName(packageName: String): String =
        "$packageName$SERVER_PROCESS_SUFFIX"

    fun shellArg(value: String): String =
        if (value.isNotEmpty() && value.all(::isShellBareChar)) {
            value
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
        }

    private fun buildClasspath(context: Context): String {
        val applicationInfo = context.applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.forEach { add(it) }
        }
        return apkPaths.joinToString(":")
    }

    private fun isShellBareChar(char: Char): Boolean =
        char in 'A'..'Z' ||
            char in 'a'..'z' ||
            char in '0'..'9' ||
            char == '/' ||
            char == '.' ||
            char == '_' ||
            char == '-' ||
            char == ':' ||
            char == '=' ||
            char == '@' ||
            char == '%' ||
            char == '+' ||
            char == ',' ||
            char == '~'

    private const val SERVER_PROCESS_SUFFIX = ":priv-kit-server"
    private const val SERVER_MAIN_CLASS = "priv.kit.server.PrivilegeServerMain"
}
