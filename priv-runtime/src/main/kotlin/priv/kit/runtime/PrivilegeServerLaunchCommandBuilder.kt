package priv.kit.runtime

import android.content.Context
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerLaunchCommand
import java.io.File

internal object PrivilegeServerLaunchCommandBuilder {
    fun build(
        context: Context,
    ): PrivilegeServerLaunchCommand {
        val packageName = context.packageName
        val userId = userIdFromUid(context.applicationInfo.uid)
        val classpath = buildClasspath(context)
        val classpathIdentity = buildClasspathIdentity(classpath)
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)
        val starterCommandLine = buildNativeStarterCommand(context)

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

    internal fun buildNativeStarterCommand(context: Context): String =
        shellArg(buildNativeStarterPath(context))

    internal fun buildNativeStarterPath(context: Context): String =
        context.applicationInfo.nativeLibraryDir.trimEnd('/') + "/" + NATIVE_STARTER_LIBRARY_NAME

    fun shellArg(value: String): String =
        if (value.isNotEmpty() && value.all(::isShellBareChar)) {
            value
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
        }

    internal fun buildClasspath(context: Context): String {
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

    private const val SERVER_MAIN_CLASS = "priv.kit.server.PrivilegeServerMain"
    private const val NATIVE_STARTER_LIBRARY_NAME = "libprivkitstarter.so"
    private const val PER_USER_RANGE = 100_000
}
