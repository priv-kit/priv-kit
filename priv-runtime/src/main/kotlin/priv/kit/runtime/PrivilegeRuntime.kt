package priv.kit.runtime

import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import priv.kit.adb.PrivilegeAdbCommand
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStartResult
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.binder.IPrivilegeServer
import priv.kit.binder.PrivilegeBinderClient
import priv.kit.binder.PrivilegeBinderEndpoint
import priv.kit.binder.PrivilegeBinderRegistration
import priv.kit.binder.PrivilegeBinderRuntime
import priv.kit.binder.PrivilegeServerDisconnectedException
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeStartupException
import priv.kit.userservice.PrivilegeUserServiceManagerUnavailableException
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStatus
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

public object PrivilegeRuntime {
    private const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
    public const val DEFAULT_FOLLOW_DEATH_DELAY_MILLIS: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS
    public const val DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH: Boolean =
        PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH
    private const val TAG = "PrivKitRuntime"
    private const val DEFAULT_USER_SERVICE_STATUS_WATCH_INTERVAL_MILLIS = 1_000L

    private val serverLock = Any()
    private var currentServer: ServerConnection? = null
    private val runtimeConfigLock = Any()
    private var currentRuntimeConfig = PrivilegeRuntimeConfig()
    private val disconnectedListeners = CopyOnWriteArraySet<() -> Unit>()
    private val binderClient = PrivilegeBinderClient()
    private val userServiceClient = PrivilegeRuntimeUserServiceClient(::getUserServiceManagerBinder)

    init {
        PrivilegeBinderRuntime.installServerProvider(::requireServerInterface)
    }

    @Throws(PrivilegeStartupException::class)
    public fun startRoot(
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var rootProcess: PrivilegeRootProcess? = null

        try {
            rootProcess = PrivilegeRootStarter.start(
                buildRootCommandLine(),
            )
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            return connectHandshake(handshakeResult)
        } catch (e: PrivilegeStartupException) {
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
    public fun createManualShellCommand(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeManualShellCommand {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        return buildManualShellCommand(
            token = token,
        )
    }

    @Throws(PrivilegeStartupException::class)
    public fun prepareManualShell(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeManualShellConnection {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val command = buildManualShellCommand(token)
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(command.token)
        return PrivilegeManualShellConnection(
            command = command,
            pendingHandshake = pendingHandshake,
            onHandshake = ::connectHandshake,
        )
    }

    @Throws(PrivilegeStartupException::class)
    public fun createExternalStartCommand(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeExternalStartCommand {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        return buildExternalStartCommand()
    }

    @Throws(PrivilegeStartupException::class)
    public fun prepareExternalStart(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeExternalStartConnection {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val command = buildExternalStartCommand()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        return PrivilegeExternalStartConnection(
            command = command,
            token = token,
            pendingHandshake = pendingHandshake,
            onHandshake = ::connectHandshake,
        )
    }

    @Throws(PrivilegeStartupException::class)
    public fun createAdbStarter(
        adbDeviceName: String? = null,
    ): PrivilegeAdbStarter =
        buildAdbStarter(
            ownerToken = ownerTokenStore().readOrCreate(),
            adbDeviceName = adbDeviceName,
        )

    @Throws(PrivilegeStartupException::class)
    public fun configureOwnerDeathBehavior(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ) {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
    }

    @Throws(PrivilegeStartupException::class)
    public fun connectReadyServer(
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo? {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return connectHandshake(handshakeResult)
    }

    @Throws(PrivilegeStartupException::class)
    public fun watchReadyServers(
        onReady: (PrivilegeServerInfo) -> Unit,
        onFailure: (Throwable) -> Unit = {},
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): Closeable {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        return PrivilegeServerHandshakeRegistry.addReadyListener(token) { handshakeResult ->
            try {
                onReady(connectHandshake(handshakeResult))
            } catch (throwable: Throwable) {
                onFailure(throwable)
            }
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo {
        applyRuntimeConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val adbStarter = buildAdbStarter(
            ownerToken = token,
            adbDeviceName = adbDeviceName,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeAdbStartResult? = null

        try {
            Log.i(TAG, "Starting through ADB keySignature=<redacted>")
            val adbStartResult = adbStarter.start(
                buildAdbCommand(),
                options,
            )
            startResult = adbStartResult
            Log.i(
                TAG,
                "ADB command completed on ${adbStartResult.host}:${adbStartResult.port}; waiting for Binder handshake",
            )
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            Log.i(TAG, "ADB Binder handshake received")
            return connectHandshake(handshakeResult)
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

    public fun getServerInfo(): PrivilegeServerInfo =
        requireServerConnection().serverInfo

    public fun pingServer(): Boolean =
        runCatching { requireServerBinder().pingBinder() }.getOrDefault(false)

    public fun addServerDisconnectedListener(listener: () -> Unit): Closeable {
        disconnectedListeners += listener
        return Closeable {
            disconnectedListeners -= listener
        }
    }

    @Throws(RemoteException::class)
    public fun shutdownServer() {
        try {
            requireServerInterface().shutdown()
        } finally {
            clearCurrentServer(notify = true)
        }
    }

    public fun registerBinderEndpoint(binder: IBinder): PrivilegeBinderRegistration =
        binderClient.register(binder)

    public fun registerBinderEndpoint(endpoint: PrivilegeBinderEndpoint): PrivilegeBinderRegistration =
        binderClient.register(endpoint)

    public fun getBinderEndpoint(): PrivilegeBinderEndpoint? =
        binderClient.get()

    public fun requireBinderEndpoint(): PrivilegeBinderEndpoint =
        binderClient.require()

    public fun unregisterBinderEndpoint(): Boolean =
        binderClient.unregister()

    public fun startUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.start(spec)

    public fun bindUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection =
        userServiceClient.bind(spec)

    public fun stopUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.stop(spec)

    public fun getUserServiceStatus(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.getStatus(spec)

    public fun watchUserServiceStatus(
        spec: PrivilegeUserServiceSpec,
        intervalMillis: Long = DEFAULT_USER_SERVICE_STATUS_WATCH_INTERVAL_MILLIS,
        onStatus: (PrivilegeUserServiceStatus) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ): Closeable {
        require(intervalMillis > 0L) { "intervalMillis must be positive" }
        val closed = AtomicBoolean(false)
        val watcher = Thread {
            while (!closed.get()) {
                try {
                    onStatus(getUserServiceStatus(spec))
                } catch (throwable: Throwable) {
                    if (!closed.get()) {
                        runCatching {
                            onFailure(throwable)
                        }
                    }
                }
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
            }
        }.apply {
            name = "priv-kit-user-service-status-watch"
            isDaemon = true
            start()
        }
        return Closeable {
            closed.set(true)
            watcher.interrupt()
        }
    }

    internal fun connectHandshake(
        handshakeResult: PrivilegeServerHandshakeResult,
    ): PrivilegeServerInfo =
        connectServer(
            serverBinder = handshakeResult.serverBinder,
            serverInfo = handshakeResult.serverInfo,
        )

    @Throws(PrivilegeStartupException::class)
    private fun connectServer(
        serverBinder: IBinder,
        serverInfo: PrivilegeServerInfo,
    ): PrivilegeServerInfo {
        val server = IPrivilegeServer.Stub.asInterface(serverBinder)
            ?: throw PrivilegeStartupException("Privileged Server returned an invalid Binder")

        if (!serverInfo.matchesCurrentRuntime()) {
            throw PrivilegeStartupException(
                "Unsupported Privileged Server protocol=${serverInfo.protocolVersion}; " +
                    "expected protocol=${PrivilegeProtocol.VERSION}",
            )
        }

        return installCurrentServer(serverInfo, server)
    }

    private fun installCurrentServer(
        serverInfo: PrivilegeServerInfo,
        server: IPrivilegeServer,
    ): PrivilegeServerInfo {
        val binder = server.asBinder()
        var previous: ServerConnection? = null
        val connection = synchronized(serverLock) {
            val current = currentServer
            if (current != null && current.server.asBinder() === binder) {
                if (binder.pingBinder()) {
                    return@synchronized current
                }
                currentServer = null
                throw PrivilegeServerDisconnectedException()
            }

            var next: ServerConnection? = null
            val deathRecipient = IBinder.DeathRecipient {
                next?.let(::markServerDisconnected)
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                throw PrivilegeServerDisconnectedException(
                    "Privilege server Binder died while connecting",
                    e,
                )
            }

            val newConnection = ServerConnection(
                serverInfo = serverInfo,
                server = server,
                deathRecipient = deathRecipient,
            )
            if (!binder.pingBinder()) {
                newConnection.unlink()
                throw PrivilegeServerDisconnectedException()
            }
            next = newConnection
            previous = current
            currentServer = newConnection
            newConnection
        }
        previous?.unlink()
        return connection.serverInfo
    }

    private fun requireServerConnection(): ServerConnection {
        val connection = synchronized(serverLock) {
            currentServer
        } ?: throw PrivilegeServerDisconnectedException()

        if (!connection.server.asBinder().pingBinder()) {
            markServerDisconnected(connection)
            throw PrivilegeServerDisconnectedException()
        }
        return connection
    }

    private fun requireServerInterface(): IPrivilegeServer =
        requireServerConnection().server

    private fun getUserServiceManagerBinder(): IBinder? =
        try {
            requireServerInterface().getUserServiceManager()
        } catch (exception: RemoteException) {
            throw PrivilegeUserServiceManagerUnavailableException(exception)
        }

    internal fun requireServerBinder(): IBinder =
        requireServerConnection().server.asBinder()

    internal fun runtimeConfig(): PrivilegeRuntimeConfig =
        synchronized(runtimeConfigLock) {
            currentRuntimeConfig
        }

    private fun markServerDisconnected(connection: ServerConnection) {
        val notify = synchronized(serverLock) {
            if (currentServer !== connection) {
                false
            } else {
                currentServer = null
                true
            }
        }
        if (notify) {
            connection.unlink()
            notifyServerDisconnected()
        }
    }

    private fun clearCurrentServer(notify: Boolean) {
        val previous = synchronized(serverLock) {
            currentServer.also {
                currentServer = null
            }
        }
        previous?.unlink()
        if (notify && previous != null) {
            notifyServerDisconnected()
        }
    }

    private fun notifyServerDisconnected() {
        disconnectedListeners.forEach { listener ->
            runCatching {
                listener()
            }
        }
    }

    private fun buildManualShellCommand(
        token: String,
    ): PrivilegeManualShellCommand {
        val launchCommand = buildServerLaunchCommand()
        return PrivilegeManualShellCommand(
            token = token,
            commandLine = buildShortNativeStarterCommand(),
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
        )
    }

    private fun buildRootCommandLine(): String =
        buildShortNativeStarterCommand()

    private fun buildAdbCommand(): PrivilegeAdbCommand {
        val launchCommand = buildServerLaunchCommand()
        return PrivilegeAdbCommand(
            commandLine = launchCommand.foregroundCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            diagnosticLogPath = null,
        )
    }

    private fun buildExternalStartCommand(): PrivilegeExternalStartCommand {
        val launchCommand = buildServerLaunchCommand()
        return PrivilegeExternalStartCommand(
            commandLine = launchCommand.detachedCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
        )
    }

    internal fun buildShortNativeStarterCommand(
        starterPath: String = buildNativeStarterPath(),
    ): String =
        PrivilegeServerLaunchCommandBuilder.shellArg(starterPath)

    private fun buildNativeStarterPath(): String =
        PrivilegeServerLaunchCommandBuilder.buildNativeStarterPath(PrivilegeRuntimeContext.require())

    private fun buildAdbStarter(
        ownerToken: String,
        adbDeviceName: String?,
    ): PrivilegeAdbStarter =
        PrivilegeAdbStarter.forOwnerToken(
            context = PrivilegeRuntimeContext.require(),
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

    private fun buildServerLaunchCommand(): PrivilegeServerLaunchCommand =
        PrivilegeServerLaunchCommandBuilder.build(
            context = PrivilegeRuntimeContext.require(),
        )

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun applyRuntimeConfig(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        val config = PrivilegeRuntimeConfig(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        synchronized(runtimeConfigLock) {
            currentRuntimeConfig = config
        }
    }

    private fun ownerTokenStore(): PrivilegeOwnerTokenStore =
        PrivilegeOwnerTokenStore(PrivilegeRuntimeContext.require())

    private data class ServerConnection(
        val serverInfo: PrivilegeServerInfo,
        val server: IPrivilegeServer,
        val deathRecipient: IBinder.DeathRecipient,
    ) {
        fun unlink() {
            try {
                server.asBinder().unlinkToDeath(deathRecipient, 0)
            } catch (_: NoSuchElementException) {
            }
        }
    }
}
