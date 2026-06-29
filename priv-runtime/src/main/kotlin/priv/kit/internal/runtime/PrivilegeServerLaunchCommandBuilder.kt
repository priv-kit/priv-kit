package priv.kit.internal.runtime

import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerLaunchCommand
import java.io.File

internal object PrivilegeServerLaunchCommandBuilder {
    fun build(): PrivilegeServerLaunchCommand {
        val context = PrivilegeRuntimeContext.require()
        val packageName = context.packageName
        val userId = userIdFromUid(context.applicationInfo.uid)
        val classpath = buildClasspath()
        val classpathIdentity = buildClasspathIdentity(classpath)
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)
        val starterCommandLine = buildNativeStarterCommand()

        return PrivilegeServerLaunchCommand(
            commandLine = starterCommandLine,
            classpath = classpath,
            classpathIdentity = classpathIdentity,
            mainClass = SERVER_MAIN_CLASS,
            providerAuthority = providerAuthority,
            packageName = packageName,
            userId = userId,
            protocolVersion = PrivilegeProtocol.VERSION,
        )
    }

    internal fun buildNativeStarterCommand(): String =
        shellArg(buildNativeStarterPath())

    internal fun buildNativeStarterPath(): String {
        val nativeLibraryDir = PrivilegeRuntimeContext.require().applicationInfo.nativeLibraryDir.trimEnd('/')
        return "$nativeLibraryDir/$NATIVE_STARTER_LIBRARY_NAME"
    }

    fun shellArg(value: String): String =
        if (value.isNotEmpty() && value.all(::isShellBareChar)) {
            value
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
        }

    internal fun buildClasspath(): String {
        val context = PrivilegeRuntimeContext.require()
        val applicationInfo = context.applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.sorted()?.forEach { add(it) }
        }
        return apkPaths.joinToString(":")
    }

    internal fun buildClasspathIdentity(classpath: String): String =
        classpath.split(':')
            .filter { it.isNotBlank() }
            .joinToString(":") { path ->
                val file = File(path)
                "$path@${file.length()}@${file.lastModified() / 1000L}"
            }

    internal fun userIdFromUid(uid: Int): Int =
        uid / PER_USER_RANGE

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

    internal const val SERVER_MAIN_CLASS = "priv.kit.internal.server.PrivilegeServerMain"
    private const val NATIVE_STARTER_LIBRARY_NAME = "libprivkitstarter.so"
    private const val PER_USER_RANGE = 100_000
}
