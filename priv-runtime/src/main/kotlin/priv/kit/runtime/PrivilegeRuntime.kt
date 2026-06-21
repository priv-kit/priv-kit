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
import priv.kit.binder.PrivilegeRemoteBinderWrapper
import priv.kit.binder.PrivilegeServerDisconnectedException
import priv.kit.core.PrivilegeLaunchMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerHandshakeResult
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.PrivilegeServerLaunchCommand
import priv.kit.core.PrivilegeStartupException
import priv.kit.delegate.PrivilegeDelegateCommand
import priv.kit.delegate.PrivilegeDelegateExecutor
import priv.kit.delegate.PrivilegeDelegateProcess
import priv.kit.delegate.PrivilegeDelegateStartResult
import priv.kit.delegate.PrivilegeDelegateStarter
import priv.kit.root.PrivilegeRootCommand
import priv.kit.root.PrivilegeRootStartResult
import priv.kit.root.PrivilegeRootStarter
import priv.kit.userservice.PrivilegeUserServiceClient
import priv.kit.userservice.PrivilegeUserServiceConnection
import priv.kit.userservice.PrivilegeUserServiceManagerUnavailableException
import priv.kit.userservice.PrivilegeUserServiceSpec
import priv.kit.userservice.PrivilegeUserServiceStatus
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

object PrivilegeRuntime {
    private const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
    const val DEFAULT_FOLLOW_DEATH_DELAY_MILLIS = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS
    const val DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH =
        PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH
    private const val STARTER_LIBRARY_NAME = "libprivkitstarter.so"
    private const val TAG = "PrivKitRuntime"
    private const val DEFAULT_USER_SERVICE_STATUS_WATCH_INTERVAL_MILLIS = 1_000L

    private val serverLock = Any()
    private var currentServer: ServerConnection? = null
    private val disconnectedListeners = CopyOnWriteArraySet<() -> Unit>()
    private val binderClient = PrivilegeBinderClient()
    private val userServiceClient = PrivilegeUserServiceClient(::getUserServiceManagerBinder)

    init {
        PrivilegeBinderRuntime.installServerProvider(::requireServerInterface)
    }

    @Throws(PrivilegeStartupException::class)
    fun startRoot(
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
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
            return connectHandshake(handshakeResult)
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
        val token = ownerTokenStore().readOrCreate()
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
            onHandshake = ::connectHandshake,
        )
    }

    @Throws(PrivilegeStartupException::class)
    fun createAdbStarter(
        adbDeviceName: String? = null,
    ): PrivilegeAdbStarter =
        buildAdbStarter(
            ownerToken = ownerTokenStore().readOrCreate(),
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
    ): PrivilegeServerInfo? {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return connectHandshake(handshakeResult)
    }

    @Throws(PrivilegeStartupException::class)
    fun watchReadyServers(
        onReady: (PrivilegeServerInfo) -> Unit,
        onFailure: (Throwable) -> Unit = {},
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): Closeable {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
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
    fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
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

    @Throws(PrivilegeStartupException::class)
    fun startDelegate(
        executor: PrivilegeDelegateExecutor,
        launchMode: PrivilegeLaunchMode = PrivilegeLaunchMode.SHELL,
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        followDeathDelayMillis: Long = DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
        activeReconnectOnOwnerDeath: Boolean = DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
    ): PrivilegeServerInfo {
        recordOwnerDeathConfig(followDeathDelayMillis, activeReconnectOnOwnerDeath)
        val token = ownerTokenStore().readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var startResult: PrivilegeDelegateStartResult? = null

        try {
            startResult = PrivilegeDelegateStarter(executor).start(
                buildDelegateCommand(
                    token = token,
                    launchMode = launchMode,
                    followDeathDelayMillis = followDeathDelayMillis,
                    activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
                ),
            )
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            return connectHandshake(handshakeResult)
        } catch (e: PrivilegeStartupException) {
            val delegateResult = startResult
            val delegateProcess = delegateResult?.process
            if (delegateProcess != null && !delegateProcess.isAlive) {
                throw PrivilegeStartupException(
                    "Delegate executor ${delegateResult.executorName} command exited before handshake: " +
                        delegateProcess.safeOutputText(),
                    e,
                )
            }
            delegateProcess?.destroy()
            throw e
        } finally {
            PrivilegeServerHandshakeRegistry.cancel(token)
        }
    }

    fun getServerInfo(): PrivilegeServerInfo =
        requireServerConnection().serverInfo

    fun pingServer(): Boolean =
        runCatching { requireServerBinder().pingBinder() }.getOrDefault(false)

    fun addServerDisconnectedListener(listener: () -> Unit): Closeable {
        disconnectedListeners += listener
        return Closeable {
            disconnectedListeners -= listener
        }
    }

    @Throws(RemoteException::class)
    fun shutdownServer() {
        try {
            requireServerInterface().shutdown()
        } finally {
            clearCurrentServer(notify = true)
        }
    }

    fun registerBinderEndpoint(binder: IBinder): PrivilegeBinderRegistration =
        binderClient.register(binder)

    fun registerBinderEndpoint(endpoint: PrivilegeBinderEndpoint): PrivilegeBinderRegistration =
        binderClient.register(endpoint)

    fun getBinderEndpoint(): PrivilegeBinderEndpoint? =
        binderClient.get()

    fun requireBinderEndpoint(): PrivilegeBinderEndpoint =
        binderClient.require()

    fun unregisterBinderEndpoint(): Boolean =
        binderClient.unregister()

    fun createRemoteBinderWrapper(targetBinder: IBinder): PrivilegeRemoteBinderWrapper =
        PrivilegeRemoteBinderWrapper(targetBinder)

    fun startUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.start(spec)

    fun bindUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection =
        userServiceClient.bind(spec)

    fun stopUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.stop(spec)

    fun getUserServiceStatus(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceStatus =
        userServiceClient.getStatus(spec)

    fun watchUserServiceStatus(
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

    internal fun connectHandshake(handshakeResult: PrivilegeServerHandshakeResult): PrivilegeServerInfo =
        connectServer(handshakeResult.serverBinder)

    @Throws(PrivilegeStartupException::class)
    private fun connectServer(serverBinder: IBinder): PrivilegeServerInfo {
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

        val connectedInfo = installCurrentServer(serverInfo, server)
        syncOwnerDeathConfig(ownerDeathConfigStore().read())
        return connectedInfo
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

    private fun syncOwnerDeathConfig(config: PrivilegeOwnerDeathConfig) {
        val server = try {
            requireServerInterface()
        } catch (_: PrivilegeServerDisconnectedException) {
            return
        }
        try {
            server.updateOwnerDeathConfig(
                config.followDeathDelayMillis,
                config.activeReconnectOnOwnerDeath,
            )
        } catch (_: RemoteException) {
            markServerDisconnected(requireServerConnection())
        }
    }

    private fun buildManualShellCommand(
        token: String,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeManualShellCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            launchMode = PrivilegeLaunchMode.SHELL,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        return PrivilegeManualShellCommand(
            token = token,
            commandLine = buildShortAdbStarterCommand(launchCommand),
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
            launchMode = PrivilegeLaunchMode.ROOT,
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
            launchMode = PrivilegeLaunchMode.SHELL,
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

    internal fun buildDelegateCommand(
        token: String,
        launchMode: PrivilegeLaunchMode,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeDelegateCommand {
        val launchCommand = buildServerLaunchCommand(
            token = token,
            launchMode = launchMode,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        return PrivilegeDelegateCommand(
            foregroundCommandLine = launchCommand.foregroundCommandLine,
            detachedCommandLine = launchCommand.detachedCommandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            launchCommand = launchCommand,
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
            append(" --user-id ")
            append(launchCommand.userId)
            append(" --launch-mode ")
            append(launchCommand.launchMode.value)
            append(" --protocol-version ")
            append(launchCommand.protocolVersion)
            append(" --server-version ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.serverVersion))
            append(" --classpath-identity ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.classpathIdentity))
            append(" --follow-death-delay-millis ")
            append(launchCommand.followDeathDelayMillis)
            append(" --active-reconnect-on-owner-death ")
            append(launchCommand.activeReconnectOnOwnerDeath)
        }

    internal fun buildShortAdbStarterCommand(
        launchCommand: PrivilegeServerLaunchCommand,
        starterPath: String = buildAdbStarterPath(),
    ): String =
        buildString {
            append(PrivilegeServerLaunchCommandBuilder.shellArg(starterPath))
            append(" --token ")
            append(PrivilegeServerLaunchCommandBuilder.shellArg(launchCommand.token))
        }

    private fun buildAdbStarterPath(): String =
        PrivilegeRuntimeContext.require().applicationInfo.nativeLibraryDir.trimEnd('/') + "/" + STARTER_LIBRARY_NAME

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

    private fun buildServerLaunchCommand(
        token: String,
        launchMode: PrivilegeLaunchMode,
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ): PrivilegeServerLaunchCommand =
        PrivilegeServerLaunchCommandBuilder.build(
            context = PrivilegeRuntimeContext.require(),
            token = token,
            launchMode = launchMode,
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )

    private fun validateFollowDeathDelayMillis(value: Long) {
        require(value >= 0L) { "followDeathDelayMillis must not be negative" }
    }

    private fun PrivilegeDelegateProcess.safeOutputText(): String =
        runCatching { outputText() }
            .getOrElse { throwable ->
                "<failed to read delegate output: ${throwable.javaClass.simpleName}: ${throwable.message}>"
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
        ownerDeathConfigStore().write(config)
        syncOwnerDeathConfig(config)
    }

    @Throws(PrivilegeStartupException::class)
    private fun readServerInfo(server: IPrivilegeServer): PrivilegeServerInfo {
        try {
            return PrivilegeServerInfo(
                uid = server.getUid(),
                pid = server.getPid(),
                launchMode = server.getLaunchMode(),
                protocolVersion = server.getProtocolVersion(),
                serverVersion = server.getServerVersion(),
            )
        } catch (e: RemoteException) {
            throw PrivilegeStartupException("Failed to read Privileged Server info", e)
        }
    }

    private fun ownerTokenStore(): PrivilegeOwnerTokenStore =
        PrivilegeOwnerTokenStore(PrivilegeRuntimeContext.require())

    private fun ownerDeathConfigStore(): PrivilegeOwnerDeathConfigStore =
        PrivilegeOwnerDeathConfigStore(PrivilegeRuntimeContext.require())

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
