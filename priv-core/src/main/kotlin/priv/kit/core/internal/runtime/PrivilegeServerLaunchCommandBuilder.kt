package priv.kit.core.internal.runtime

import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeServerLaunchCommand

internal object PrivilegeServerLaunchCommandBuilder {
    fun build(launchCorrelationId: String): PrivilegeServerLaunchCommand {
        val context = PrivilegeContext.require()
        val packageName = context.packageName
        val classpath = buildClasspath()
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)
        val starterCommandLine = buildNativeStarterCommand(
            launchCorrelationId = launchCorrelationId,
            clearInheritedLaunchCorrelationId = false,
        )

        return PrivilegeServerLaunchCommand(
            commandLine = starterCommandLine,
            classpath = classpath,
            mainClass = SERVER_MAIN_CLASS,
            providerAuthority = providerAuthority,
        )
    }

    internal fun buildNativeStarterCommand(
        launchCorrelationId: String?,
        clearInheritedLaunchCorrelationId: Boolean,
    ): String = buildNativeStarterCommand(
        starterPath = buildNativeStarterPath(),
        launchCorrelationId = launchCorrelationId,
        clearInheritedLaunchCorrelationId = clearInheritedLaunchCorrelationId,
    )

    internal fun buildNativeStarterCommand(
        starterPath: String,
        launchCorrelationId: String?,
        clearInheritedLaunchCorrelationId: Boolean,
    ): String {
        val starter = shellArg(starterPath)
        if (launchCorrelationId == null && !clearInheritedLaunchCorrelationId) return starter
        return "${PrivilegeHandshakeContract.ENV_LAUNCH_CORRELATION_ID}=" +
            shellArg(launchCorrelationId.orEmpty()) +
            " " +
            starter
    }

    internal fun buildNativeStarterPath(): String {
        val nativeLibraryDir = PrivilegeContext.require().applicationInfo.nativeLibraryDir.trimEnd('/')
        return "$nativeLibraryDir/$NATIVE_STARTER_LIBRARY_NAME"
    }

    fun shellArg(value: String): String =
        if (value.isNotEmpty() && value.all(::isShellBareChar)) {
            value
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
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

    internal const val SERVER_MAIN_CLASS = "priv.kit.core.internal.server.PrivilegeServerMain"
    private const val NATIVE_STARTER_LIBRARY_NAME = "libprivkitstarter.so"
}
