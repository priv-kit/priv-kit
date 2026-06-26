package priv.kit.runtime

import android.content.Context
import android.net.nsd.NsdManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import priv.kit.adb.PrivilegeAdbCommand
import priv.kit.adb.PrivilegeAdbIdentity
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStartResult
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.binder.IPrivilegeServer
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
    private const val TAG = "PrivKitRuntime"
    private const val DEFAULT_USER_SERVICE_STATUS_WATCH_INTERVAL_MILLIS = 1_000L

    private val serverLock = Any()
    private var currentServer: ServerConnection? = null
    private val disconnectedListeners = CopyOnWriteArraySet<() -> Unit>()
    private val userServiceClient = PrivilegeRuntimeUserServiceClient(::getUserServiceManagerBinder)

    init {
        PrivilegeBinderRuntime.installServerProvider(::requireServerInterface)
    }

    @Throws(PrivilegeStartupException::class)
    public fun startRoot(
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
    ): PrivilegeServerInfo {
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
    public fun createShellStartCommand(): String {
        ownerTokenStore().readOrCreate()
        return buildShellStartCommandLine()
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
    public fun connectReadyServer(): PrivilegeServerInfo? {
        val token = ownerTokenStore().readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return connectHandshake(handshakeResult)
    }

    @Throws(PrivilegeStartupException::class)
    public fun addServerConnectedListener(
        onConnected: (PrivilegeServerInfo) -> Unit,
    ): Closeable {
        val token = ownerTokenStore().readOrCreate()
        return PrivilegeServerHandshakeRegistry.addReadyListener(token) { handshakeResult ->
            try {
                onConnected(connectHandshake(handshakeResult))
            } catch (throwable: Throwable) {
                Log.e(TAG, "Server connection handoff failed", throwable)
            }
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
    ): PrivilegeServerInfo {
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

    public fun pingServer(): Boolean {
        val connection = synchronized(serverLock) {
            currentServer
        } ?: return false
        if (runCatching { connection.server.asBinder().pingBinder() }.getOrDefault(false)) {
            return true
        }
        markServerDisconnected(connection)
        return false
    }

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
            clearCurrentServer()
        }
    }

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
        return synchronized(serverLock) {
            currentServer
        } ?: throw PrivilegeServerDisconnectedException()
    }

    private fun requireServerInterface(): IPrivilegeServer =
        requireServerConnection().server

    private fun getUserServiceManagerBinder(): IBinder? =
        try {
            requireServerInterface().getUserServiceManager()
        } catch (exception: RemoteException) {
            throw PrivilegeUserServiceManagerUnavailableException(exception)
        }

    internal fun runtimeConfig(): PrivilegeRuntimeConfigSnapshot =
        PrivilegeRuntimeConfig.snapshot()

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

    private fun clearCurrentServer() {
        val previous = synchronized(serverLock) {
            currentServer.also {
                currentServer = null
            }
        }
        previous?.unlink()
        if (previous != null) {
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

    private fun buildShellStartCommandLine(): String =
        buildShortNativeStarterCommand()

    private fun buildRootCommandLine(): String =
        buildShortNativeStarterCommand()

    private fun buildAdbCommand(): PrivilegeAdbCommand {
        val launchCommand = buildServerLaunchCommand()
        return PrivilegeAdbCommand(
            commandLine = launchCommand.commandLine,
            classpath = launchCommand.classpath,
            mainClass = launchCommand.mainClass,
            providerAuthority = launchCommand.providerAuthority,
            diagnosticLogPath = null,
        )
    }

    internal fun buildShortNativeStarterCommand(
        starterPath: String = buildNativeStarterPath(),
    ): String =
        PrivilegeServerLaunchCommandBuilder.shellArg(starterPath)

    private fun buildNativeStarterPath(): String =
        PrivilegeServerLaunchCommandBuilder.buildNativeStarterPath()

    private fun buildAdbStarter(
        ownerToken: String,
        adbDeviceName: String?,
    ): PrivilegeAdbStarter {
        val context = PrivilegeRuntimeContext.require()
        return PrivilegeAdbStarter.forOwnerToken(
            ownerToken = ownerToken,
            adbDeviceName = resolveAdbDeviceName(context, adbDeviceName),
            loadKeyBytes = { PrivilegeAdbKeyStore.readOrCreate() },
            nsdManagerProvider = { requireAdbNsdManager(context) },
        )
    }

    private fun requireAdbNsdManager(context: Context): NsdManager =
        context.applicationContext.getSystemService(NsdManager::class.java)
            ?: throw PrivilegeStartupException("NSD manager is unavailable")

    private fun resolveAdbDeviceName(
        context: Context,
        adbDeviceName: String?,
    ): String {
        val requestedName = adbDeviceName?.trim()
        if (!requestedName.isNullOrEmpty()) return requestedName

        val applicationContext = context.applicationContext
        val appLabel = runCatching {
            applicationContext.applicationInfo
                .loadLabel(applicationContext.packageManager)
                .toString()
        }.getOrNull()
        return appLabel.toSafeDefaultAdbDeviceName()
            ?: applicationContext.packageName.toSafeDefaultAdbDeviceName()
            ?: PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME
    }

    private fun String?.toSafeDefaultAdbDeviceName(): String? {
        val value = this
            ?.replace('\u0000', ' ')
            ?.replace('\r', ' ')
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(PrivilegeAdbIdentity.MAX_DEVICE_NAME_LENGTH)
        return value?.ifBlank { null }
    }

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
        PrivilegeServerLaunchCommandBuilder.build()

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun ownerTokenStore(): PrivilegeOwnerTokenStore =
        PrivilegeOwnerTokenStore

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
