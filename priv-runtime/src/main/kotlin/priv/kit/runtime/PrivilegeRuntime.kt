package priv.kit.runtime

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import priv.kit.core.IPrivilegeServer
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeStartupException
import priv.kit.adb.PrivilegeAdbCommand
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStartResult
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.root.PrivilegeRootCommand
import priv.kit.root.PrivilegeRootStartResult
import priv.kit.root.PrivilegeRootStarter
import java.io.Closeable

class PrivilegeRuntime private constructor(
    private val context: Context,
) {
    private val ownerTokenStore = PrivilegeOwnerTokenStore(context)

    @Throws(PrivilegeStartupException::class)
    fun startRoot(timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS): PrivilegeSession {
        val token = ownerTokenStore.readOrCreate()
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

    @Throws(PrivilegeStartupException::class)
    fun prepareManualShell(): PrivilegeManualShellConnection {
        val token = ownerTokenStore.readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        val command = buildManualShellCommand(token)
        return PrivilegeManualShellConnection(
            command = command,
            pendingHandshake = pendingHandshake,
            onHandshake = ::createSession,
        )
    }

    @Throws(PrivilegeStartupException::class)
    fun createAdbStarter(adbDeviceName: String? = null): PrivilegeAdbStarter =
        buildAdbStarter(
            ownerToken = ownerTokenStore.readOrCreate(),
            adbDeviceName = adbDeviceName,
        )

    @Throws(PrivilegeStartupException::class)
    fun connectReadyServer(): PrivilegeSession? {
        val token = ownerTokenStore.readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return createSession(handshakeResult)
    }

    @Throws(PrivilegeStartupException::class)
    fun watchReadyServers(
        onReady: (PrivilegeSession) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Closeable {
        val token = ownerTokenStore.readOrCreate()
        return PrivilegeServerHandshakeRegistry.addReadyListener(token) { handshakeResult ->
            try {
                onReady(createSession(handshakeResult))
            } catch (throwable: Throwable) {
                onFailure(throwable)
            }
        }
    }

    @Throws(PrivilegeStartupException::class)
    fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
    ): PrivilegeSession {
        val token = ownerTokenStore.readOrCreate()
        val adbStarter = buildAdbStarter(
            ownerToken = token,
            adbDeviceName = adbDeviceName,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeAdbStartResult? = null

        try {
            Log.i(TAG, "Starting through ADB keySignature=<redacted>")
            val adbStartResult = adbStarter.start(buildAdbCommand(token), options)
            startResult = adbStartResult
            Log.i(
                TAG,
                "ADB command completed on ${adbStartResult.host}:${adbStartResult.port}; waiting for Binder handshake",
            )
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            Log.i(TAG, "ADB Binder handshake received")
            return createSession(handshakeResult)
        } catch (e: PrivilegeStartupException) {
            Log.e(TAG, "ADB startup failed", e)
            val adbResult = startResult
            if (adbResult != null) {
                val serverLog = readAdbServerDiagnosticLog(adbResult, adbStarter)
                throw PrivilegeStartupException(
                    "ADB start did not complete the Privileged Server handshake on " +
                        "${adbResult.host}:${adbResult.port}: ${adbResult.output.text()}$serverLog",
                    e,
                )
            }
            throw e
        } finally {
            PrivilegeServerHandshakeRegistry.cancel(token)
        }
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
            commandLine = buildShortAdbStarterCommand(token),
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

    private fun buildAdbCommand(token: String): PrivilegeAdbCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.SHELL,
        )
        val diagnosticLogPath = PrivilegeAdbStarter.DIAGNOSTIC_LOG_PREFIX +
            System.currentTimeMillis() +
            ".log"
        return PrivilegeAdbCommand(
            commandLine = buildAdbStarterCommand(
                launchCommand = launchCommand,
                starterPath = buildAdbStarterPath(),
                diagnosticLogPath = diagnosticLogPath,
            ),
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
            diagnosticLogPath = diagnosticLogPath,
        )
    }

    private fun buildAdbStarterCommand(
        launchCommand: PrivilegeServerLaunchCommand,
        starterPath: String,
        diagnosticLogPath: String,
    ): String =
        buildString {
            append(shellArg(starterPath))
            append(" --classpath ")
            append(shellArg(launchCommand.classpath))
            append(" --main-class ")
            append(shellArg(launchCommand.mainClass))
            append(" --process-name ")
            append(shellArg(buildServerProcessName(launchCommand.packageName)))
            append(" --log-path ")
            append(shellArg(diagnosticLogPath))
            append(" --")
            append(" --token ")
            append(shellArg(launchCommand.token))
            append(" --provider-authority ")
            append(shellArg(launchCommand.providerAuthority))
            append(" --package-name ")
            append(shellArg(launchCommand.packageName))
            append(" --mode ")
            append(launchCommand.mode.value)
            append(" --protocol-version ")
            append(launchCommand.protocolVersion)
            append(" --server-version ")
            append(shellArg(launchCommand.serverVersion))
        }

    private fun buildShortAdbStarterCommand(token: String): String =
        buildString {
            append(shellArg(buildAdbStarterPath()))
            append(" --token ")
            append(shellArg(token))
        }

    private fun buildAdbStarterPath(): String =
        context.applicationInfo.nativeLibraryDir.trimEnd('/') + "/" + STARTER_LIBRARY_NAME

    private fun buildAdbStarter(
        ownerToken: String,
        adbDeviceName: String?,
    ): PrivilegeAdbStarter =
        PrivilegeAdbStarter.forOwnerToken(
            context = context,
            ownerToken = ownerToken,
            adbDeviceName = adbDeviceName,
        )

    private fun readAdbServerDiagnosticLog(
        adbResult: PrivilegeAdbStartResult,
        adbStarter: PrivilegeAdbStarter,
    ): String {
        val path = adbResult.command.diagnosticLogPath ?: return ""
        val output = runCatching {
            adbStarter.readDiagnosticLog(
                host = adbResult.host,
                port = adbResult.port,
                path = path,
            )
                .text()
        }.getOrElse { throwable ->
            "[diag] Failed to fetch server diagnostic log: ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        return "\n[server diagnostic log: $path]\n$output"
    }

    private fun buildServerLaunchCommand(
        token: String,
        mode: PrivilegeMode,
    ): PrivilegeServerLaunchCommand {
        val packageName = context.packageName
        val classpath = buildClasspath()
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

    private fun shellArg(value: String): String =
        if (value.isNotEmpty() && value.all(::isShellBareChar)) {
            value
        } else {
            "'" + value.replace("'", "'\"'\"'") + "'"
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

    private fun buildServerProcessName(packageName: String): String =
        "$packageName$SERVER_PROCESS_SUFFIX"

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
        private const val SERVER_PROCESS_SUFFIX = ":priv-kit-server"
        private const val SERVER_MAIN_CLASS = "priv.kit.server.PrivilegeServerMain"
        private const val STARTER_LIBRARY_NAME = "libprivkitstarter.so"
        private const val TAG = "PrivKitRuntime"

        fun create(context: Context): PrivilegeRuntime =
            PrivilegeRuntime(context.applicationContext)
    }
}
