package priv.kit.runtime

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import priv.kit.core.IPrivilegeServer
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
    private val ownerDeathConfigStore = PrivilegeOwnerDeathConfigStore(context)

    @Throws(PrivilegeStartupException::class)
    fun startRoot(
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeSession {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore.readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeRootStartResult? = null

        try {
            startResult = PrivilegeRootStarter().start(
                buildRootCommand(
                    token = token,
                    followDeathDelayMillis = followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
                ),
            )
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
    fun createManualShellCommand(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeManualShellCommand {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore.readOrCreate()
        return buildManualShellCommand(
            token = token,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
    }

    @Throws(PrivilegeStartupException::class)
    fun prepareManualShell(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeManualShellConnection {
        val command = createManualShellCommand(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(command.token)
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
    fun configureOwnerDeathBehavior(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ) {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
    }

    @Throws(PrivilegeStartupException::class)
    fun connectReadyServer(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeSession? {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore.readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return createSession(handshakeResult)
    }

    @Throws(PrivilegeStartupException::class)
    fun watchReadyServers(
        onReady: (PrivilegeSession) -> Unit,
        onFailure: (Throwable) -> Unit = {},
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): Closeable {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
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
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeSession {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore.readOrCreate()
        val adbStarter = buildAdbStarter(
            ownerToken = token,
            adbDeviceName = adbDeviceName,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeAdbStartResult? = null

        try {
            Log.i(TAG, "Starting through ADB keySignature=<redacted>")
            val adbStartResult = adbStarter.start(
                buildAdbCommand(
                    token = token,
                    followDeathDelayMillis = followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
                ),
                options,
            )
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
        if (!serverInfo.matchesCurrentRuntime()) {
            throw PrivilegeStartupException(
                "Unsupported Privileged Server version protocol=${serverInfo.protocolVersion}, " +
                    "server=${serverInfo.serverVersion}; expected protocol=${PrivilegeProtocol.VERSION}, " +
                    "server=${PrivilegeProtocol.SERVER_VERSION}",
            )
        }

        return PrivilegeSession(
            serverInfo = serverInfo,
            serverBinder = server,
        ).also { session ->
            session.syncOwnerDeathConfig(ownerDeathConfigStore.read())
        }
    }

    private fun buildManualShellCommand(
        token: String,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeManualShellCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.SHELL,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
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

    private fun buildRootCommand(
        token: String,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeRootCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.ROOT,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        return PrivilegeRootCommand(
            commandLine = launchCommand.foregroundCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
        )
    }

    private fun buildAdbCommand(
        token: String,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeAdbCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            mode = PrivilegeMode.SHELL,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
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
            append(PrivilegeServerLaunchCommandBuilder.shellArg(starterPath))
            append(" --classpath ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.classpath))
            append(" --main-class ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.mainClass))
            append(" --process-name ")
            append(
                PrivilegeServerLaunchCommandBuilder.shellArg(
                    PrivilegeServerLaunchCommandBuilder.buildServerProcessName(launchCommand.packageName),
                ),
            )
            append(" --log-path ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(diagnosticLogPath))
            append(" --")
            append(" --token ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.token))
            append(" --provider-authority ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.providerAuthority))
            append(" --package-name ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.packageName))
            append(" --mode ")
            append(launchCommand.mode.value)
            append(" --protocol-version ")
            append(launchCommand.protocolVersion)
            append(" --server-version ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.serverVersion))
            append(" --follow-death-delay-millis ")
            append(launchCommand.followDeathDelayMillis)
            append(" --active-reconnect-on-owner-death ")
            append(launchCommand.activeReconnectOnOwnerDeath)
        }

    private fun buildShortAdbStarterCommand(token: String): String =
        buildString {
            append(PrivilegeServerLaunchCommandBuilder.shellArg(buildAdbStarterPath()))
            append(" --token ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(token))
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
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeServerLaunchCommand =
        PrivilegeServerLaunchCommandBuilder.build(
            context = context,
            token = token,
            mode = mode,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )

    private fun validateFollowDeathDelayMillis(value: Long) {
        require(value >= 0L) { "followDeathDelayMillis must not be negative" }
    }

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION &&
            serverVersion == PrivilegeProtocol.SERVER_VERSION

    private fun recordOwnerDeathConfig(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        validateFollowDeathDelayMillis(followDeathDelayMillis)
        val config = PrivilegeOwnerDeathConfig(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        ownerDeathConfigStore.write(config)
        PrivilegeSession.syncOwnerDeathConfigToConnectedSessions(config)
    }

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
        const val DEFAULT_FOLLOW_DEATH_DELAY_MILLIS = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS
        const val DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH =
            PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH
        private const val STARTER_LIBRARY_NAME = "libprivkitstarter.so"
        private const val TAG = "PrivKitRuntime"

        fun create(context: Context): PrivilegeRuntime =
            PrivilegeRuntime(context.applicationContext)
    }
}
