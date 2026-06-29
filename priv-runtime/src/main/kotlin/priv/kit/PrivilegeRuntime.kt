package priv.kit

import android.content.Context
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import priv.kit.adb.PrivilegeAdbIdentity
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStartResult
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.internal.binder.IPrivilegeServer
import priv.kit.internal.binder.PrivilegeBinderRuntime
import priv.kit.binder.PrivilegeServerDisconnectedException
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.internal.core.PrivilegeServerHandshakeResult
import priv.kit.internal.core.PrivilegeServerLaunchCommand
import priv.kit.internal.runtime.PrivilegeOwnerTokenStore
import priv.kit.internal.runtime.PrivilegeRootProcess
import priv.kit.internal.runtime.PrivilegeRootStarter
import priv.kit.internal.runtime.PrivilegeRuntimeContext
import priv.kit.internal.runtime.PrivilegeRuntimeUserServiceClient
import priv.kit.internal.runtime.PrivilegeServerLaunchCommandBuilder
import priv.kit.userservice.PrivilegeUserServiceManagerUnavailableException
import priv.kit.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

public object PrivilegeRuntime {
    private const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
    private const val TAG = "PrivKitRuntime"

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
    ): PrivilegeServerInfo =
        startRoot(
            timeoutMillis = timeoutMillis,
            startupLogListener = null,
        )

    @Throws(PrivilegeStartupException::class)
    public fun startRoot(
        timeoutMillis: Long,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val token = ownerTokenStore().readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var rootProcess: PrivilegeRootProcess? = null

        try {
            startupLogListener.emitStartupLog("runtime", "Starting with root")
            rootProcess = PrivilegeRootStarter.start(
                buildRootCommandLine(),
                startupLogListener = startupLogListener,
            )
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            return connectHandshake(handshakeResult)
        } catch (e: PrivilegeStartupException) {
            startupLogListener.emitStartupLog("runtime", "Startup failed: ${e.message.orEmpty()}")
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
    ): PrivilegeServerInfo =
        startAdb(
            options = options,
            timeoutMillis = timeoutMillis,
            adbDeviceName = adbDeviceName,
            startupLogListener = null,
        )

    @Throws(PrivilegeStartupException::class)
    public fun startAdb(
        options: PrivilegeAdbStartOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
        startupLogListener: PrivilegeStartupLogListener?,
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
            startupLogListener.emitStartupLog("runtime", "Starting through ADB")
            val adbStartResult = adbStarter.start(
                buildServerLaunchCommand(),
                options,
                startupLogListener = startupLogListener,
            )
            startResult = adbStartResult
            Log.i(
                TAG,
                "ADB command completed on ${adbStartResult.host}:${adbStartResult.port}; waiting for Binder handshake",
            )
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            Log.i(TAG, "ADB Binder handshake received")
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            return connectHandshake(handshakeResult)
        } catch (e: PrivilegeStartupException) {
            Log.e(TAG, "ADB startup failed", e)
            startupLogListener.emitStartupLog("runtime", "ADB startup failed: ${e.message.orEmpty()}")
            val adbResult = startResult
            if (adbResult != null) {
                val serverDiagnostics = readAdbServerDiagnostics(
                    adbResult = adbResult,
                    adbStarter = adbStarter,
                    startupLogListener = startupLogListener,
                )
                throw PrivilegeStartupException(
                    "ADB start did not complete the Privileged Server handshake on " +
                        "${adbResult.host}:${adbResult.port}: ${adbResult.outputText}$serverDiagnostics",
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

    public fun startUserService(spec: PrivilegeUserServiceSpec) {
        userServiceClient.start(spec)
    }

    public fun bindUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection =
        userServiceClient.bind(spec)

    public fun stopUserService(spec: PrivilegeUserServiceSpec) {
        userServiceClient.stop(spec)
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
            context = context,
            adbDeviceName = resolveAdbDeviceName(context, adbDeviceName),
        )
    }

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

    private fun readAdbServerDiagnostics(
        adbResult: PrivilegeAdbStartResult,
        adbStarter: PrivilegeAdbStarter,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        val output = runCatching {
            adbStarter.readRuntimeDiagnostics(
                host = adbResult.host,
                port = adbResult.port,
                startupLogListener = startupLogListener,
            )
        }.getOrElse { throwable ->
            "[diag] Failed to fetch server diagnostics: ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        return "\n[server diagnostics]\n$output"
    }

    private fun buildServerLaunchCommand(): PrivilegeServerLaunchCommand =
        PrivilegeServerLaunchCommandBuilder.build()

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun PrivilegeStartupLogListener?.emitStartupLog(
        source: String,
        message: String,
    ) {
        this?.onLog(
            PrivilegeStartupLogLine(
                source = source,
                message = message,
            ),
        )
    }

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
