package priv.kit.runtime

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import priv.kit.core.IPrivilegeServer
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException
import priv.kit.core.PrivilegeToken
import priv.kit.root.PrivilegeRootCommand
import priv.kit.root.PrivilegeRootStartResult
import priv.kit.root.PrivilegeRootStarter

class PrivilegeRuntime private constructor(
    private val context: Context,
) {
    @Throws(PrivilegeStartupException::class)
    fun startRoot(timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS): PrivilegeSession {
        val token = PrivilegeToken.generate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeRootStartResult? = null

        try {
            startResult = PrivilegeRootStarter().start(buildRootCommand(token))
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            return createSession(handshakeResult)
        } catch (e: PrivilegeStartupException) {
            val rootProcess = startResult?.process
            if (rootProcess != null && !rootProcess.isAlive) {
                throw PrivilegeStartupException(
                    "Privileged Server command exited before handshake: ${rootProcess.outputText()}",
                    e,
                )
            }
            rootProcess?.destroy()
            throw e
        } finally {
            PrivilegeServerHandshakeRegistry.cancel(token)
        }
    }

    fun prepareManualShell(): PrivilegeManualShellConnection {
        val token = PrivilegeToken.generate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        val command = buildManualShellCommand(token)
        return PrivilegeManualShellConnection(
            command = command,
            pendingHandshake = pendingHandshake,
            onHandshake = ::createSession,
        )
    }

    @Throws(PrivilegeStartupException::class)
    internal fun createSession(handshakeResult: PrivilegeServerHandshakeResult): PrivilegeSession =
        createSession(handshakeResult.serverBinder)

    @Throws(PrivilegeStartupException::class)
    private fun createSession(serverBinder: IBinder): PrivilegeSession {
        val server = IPrivilegeServer.Stub.asInterface(serverBinder)
            ?: throw PrivilegeStartupException("Privileged Server returned an invalid Binder")

        val serverInfo = readServerInfo(server)
        if (serverInfo.protocolVersion != PrivilegeProtocol.VERSION) {
            throw PrivilegeStartupException(
                "Unsupported protocol version ${serverInfo.protocolVersion}; expected ${PrivilegeProtocol.VERSION}",
            )
        }

        return PrivilegeSession(
            serverInfo = serverInfo,
            serverBinder = server,
        )
    }

    private fun buildManualShellCommand(token: String): PrivilegeManualShellCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.SHELL,
        )
        return PrivilegeManualShellCommand(
            token = token,
            commandLine = launchCommand.detachedCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
        )
    }

    private fun buildRootCommand(token: String): PrivilegeRootCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.ROOT,
        )
        return PrivilegeRootCommand(
            commandLine = launchCommand.foregroundCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
        )
    }

    private fun buildServerLaunchCommand(
        token: String,
        mode: PrivilegeMode,
    ): PrivilegeServerLaunchCommand {
        val packageName = context.packageName
        val classpath = buildClasspath()
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(packageName)
        val foregroundCommandLine = buildString {
            append("CLASSPATH=")
            append(shellQuote(classpath))
            append(" /system/bin/app_process /system/bin")
            append(" --nice-name=")
            append(shellQuote(SERVER_PROCESS_NAME))
            append(' ')
            append(shellQuote(SERVER_MAIN_CLASS))
            append(" --token ")
            append(shellQuote(token))
            append(" --provider-authority ")
            append(shellQuote(providerAuthority))
            append(" --package-name ")
            append(shellQuote(packageName))
            append(" --mode ")
            append(mode.value)
            append(" --protocol-version ")
            append(PrivilegeProtocol.VERSION)
            append(" --server-version ")
            append(shellQuote(PrivilegeProtocol.SERVER_VERSION))
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
        )
    }

    private fun buildClasspath(): String {
        val applicationInfo = context.applicationInfo
        val apkPaths = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs?.forEach { add(it) }
        }
        return apkPaths.joinToString(":")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    @Throws(PrivilegeStartupException::class)
    private fun readServerInfo(server: IPrivilegeServer): PrivilegeServerInfo {
        try {
            return PrivilegeServerInfo(
                uid = server.getUid(),
                pid = server.getPid(),
                mode = server.getMode(),
                protocolVersion = server.getProtocolVersion(),
                serverVersion = server.getServerVersion(),
            )
        } catch (e: RemoteException) {
            throw PrivilegeStartupException("Failed to read Privileged Server info", e)
        }
    }

    companion object {
        private const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
        private const val SERVER_PROCESS_NAME = "priv-kit-server"
        private const val SERVER_MAIN_CLASS = "priv.kit.server.PrivilegeServerMain"

        fun create(context: Context): PrivilegeRuntime =
            PrivilegeRuntime(context.applicationContext)
    }
}
