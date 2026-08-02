package priv.kit.core

import android.Manifest
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.WorkerThread
import priv.kit.core.adb.PrivilegeAdbIdentity
import priv.kit.core.adb.PrivilegeAdbConnectionOptions
import priv.kit.core.adb.PrivilegeAdbStartResult
import priv.kit.core.adb.PrivilegeAdbManager
import priv.kit.core.binder.serverUnavailable
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.core.PrivilegeAndroidUsers
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegePendingHandshake
import priv.kit.core.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.internal.runtime.PrivilegeRootProcess
import priv.kit.core.internal.runtime.PrivilegeRootStarter
import priv.kit.core.internal.runtime.PrivilegeStarterContract
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionEvent
import priv.kit.core.internal.runtime.PrivilegeRuntimeConnectionOrigin
import priv.kit.core.internal.runtime.PrivilegeRuntimeStartCoordinator
import priv.kit.core.internal.runtime.PrivilegeUserServiceClient
import priv.kit.core.internal.runtime.PrivilegeServerLaunchCommandBuilder
import priv.kit.shared.PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS
import priv.kit.shared.PRIVILEGE_INTERNAL_ROOT_UID
import priv.kit.shared.PrivilegeManifestPermissions
import priv.kit.shared.toPrivilegeAdbDeviceNameText
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select

public object Privilege {
    private const val GRANT_RUNTIME_PERMISSIONS = "android.permission.GRANT_RUNTIME_PERMISSIONS"
    private const val TAG = "PrivKit"

    private val serverLock = Any()
    private val currentUserId: Int by lazy {
        Process.myUserHandle().hashCode()
    }
    private var currentServer: ServerConnection? = null
    private val mutableServerState = MutableStateFlow<PrivilegeServerInfo?>(null)
    private val serverStateFlow = mutableServerState.asStateFlow()
    private val mutableServerConnectionEvents = MutableSharedFlow<PrivilegeRuntimeConnectionEvent>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    internal val serverConnectionEvents: SharedFlow<PrivilegeRuntimeConnectionEvent> =
        mutableServerConnectionEvents.asSharedFlow()
    private val runtimeConnectionListenerLock = Any()
    private var runtimeConnectionListener: Closeable? = null
    private val userServiceClient = PrivilegeUserServiceClient(::getUserServiceManagerBinder)

    @Throws(PrivilegeStartupException::class)
    public suspend fun startRoot(
        timeoutMillis: Long = PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo = startRootWithLaunchCorrelationId(
        launchCorrelationId = PrivilegeRuntimeStartCoordinator.newLaunchCorrelationId(),
        timeoutMillis = timeoutMillis,
        startupLogListener = startupLogListener,
    )

    internal suspend fun startRootWithLaunchCorrelationId(
        launchCorrelationId: String,
        timeoutMillis: Long,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(launchCorrelationId)
        var rootProcess: PrivilegeRootProcess? = null
        var startupCompleted = false

        try {
            startupLogListener.emitStartupLog("runtime", "Starting with root")
            rootProcess = runInterruptible(Dispatchers.IO) {
                PrivilegeRootStarter.start(
                    createNativeStarterCommand(
                        launchCorrelationId = launchCorrelationId,
                    ),
                    startupLogListener = startupLogListener,
                )
            }
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = awaitRootHandshakeOrStarterExit(
                pendingHandshake = pendingHandshake,
                rootProcess = checkNotNull(rootProcess),
                timeoutMillis = timeoutMillis,
            )
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            val serverInfo = connectHandshake(handshakeResult, startupLogListener)
            PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)
            startupCompleted = true
            return serverInfo
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: PrivilegeStartupException) {
            startupLogListener.emitStartupLog("runtime", "Startup failed: ${e.message.orEmpty()}")
            if (e is PrivilegeExistingServerStopException) {
                throw e
            }
            val process = rootProcess
            if (process != null && rootServerLaunchMayHaveCompleted(
                    processIsAlive = process.isAlive,
                    exitCode = process.exitCodeOrNull,
                )
            ) {
                throw PrivilegeServerLaunchUncertainException(
                    "Root server launch may have completed before the Binder handshake",
                    e,
                )
            }
            if (process != null) {
                throw PrivilegeStartupException(
                    "Privileged Server command exited before handshake: ${process.outputText()}",
                    e,
                )
            }
            throw e
        } finally {
            val deliveredServerPreserved =
                PrivilegeServerHandshakeRegistry.cancel(launchCorrelationId)
            cleanupRootProcessAfterStart(
                process = rootProcess,
                startupCompleted = startupCompleted || deliveredServerPreserved,
            )
        }
    }

    private val baseNativeStarterCommand: String by lazy {
        PrivilegeServerLaunchCommandBuilder.resolveNativeStarterCommand()
    }

    /**
     * A device-side shell command that starts the native starter.
     *
     * On Android 10 and later this command runs an uncompressed starter directly from an APK
     * through the platform linker when no extracted file exists. On Android 8, 8.1, and 9 the
     * host app must set `packaging.jniLibs.useLegacyPackaging = true`.
     *
     * The command is resolved on first access and cached for the lifetime of this process.
     * First access inspects the installed APKs and must run off the main thread.
     * For a non-primary Android user, it includes the current application's owner user ID so the
     * starter remains scoped when the command is executed after this process exits. User 0 uses
     * the starter's default and omits that environment variable.
     * Host UI can prefix it with `adb shell ` when presenting a command for a development
     * machine. The starter only runs as root (UID 0), system (UID 1000), or shell (UID 2000).
     */
    @get:Throws(PrivilegeStartupException::class)
    @get:WorkerThread
    public val nativeStarterCommand: String by lazy {
        PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
            baseNativeStarterCommand = baseNativeStarterCommand,
            launchCorrelationId = null,
        )
    }

    internal fun createNativeStarterCommand(
        launchCorrelationId: String?,
    ): String =
        PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
            baseNativeStarterCommand = baseNativeStarterCommand,
            launchCorrelationId = launchCorrelationId,
        )

    @Throws(PrivilegeStartupException::class)
    public fun createAdbManager(
        adbDeviceName: String? = null,
    ): PrivilegeAdbManager =
        buildAdbManager(adbDeviceName = adbDeviceName)

    @Throws(PrivilegeStartupException::class)
    public fun connectReadyServer(): PrivilegeServerInfo? {
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady() ?: return null
        return connectHandshake(
            handshakeResult = handshakeResult,
            startupLogListener = null,
        )
    }

    public val serverState: StateFlow<PrivilegeServerInfo?>
        get() {
            initializeRuntimeConnection()
            return serverStateFlow
        }

    @Throws(PrivilegeStartupException::class)
    public suspend fun startAdb(
        options: PrivilegeAdbConnectionOptions = PrivilegeAdbConnectionOptions(),
        timeoutMillis: Long = PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo = startAdbWithLaunchCorrelationId(
        launchCorrelationId = PrivilegeRuntimeStartCoordinator.newLaunchCorrelationId(),
        options = options,
        timeoutMillis = timeoutMillis,
        adbDeviceName = adbDeviceName,
        startupLogListener = startupLogListener,
    )

    internal suspend fun startAdbWithLaunchCorrelationId(
        launchCorrelationId: String,
        options: PrivilegeAdbConnectionOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val adbManager = buildAdbManager(
            adbDeviceName = adbDeviceName,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(launchCorrelationId)
        var startResult: PrivilegeAdbStartResult? = null

        try {
            Log.i(TAG, "Starting through ADB keySignature=<redacted>")
            startupLogListener.emitStartupLog("runtime", "Starting through ADB")
            val launchCommand = runInterruptible(Dispatchers.IO) {
                PrivilegeServerLaunchCommandBuilder.build(
                    starterCommandLine = createNativeStarterCommand(
                        launchCorrelationId = launchCorrelationId,
                    ),
                )
            }
            val adbStartResult = adbManager.start(
                launchCommand,
                options,
                startupLogListener = startupLogListener,
            )
            startResult = adbStartResult
            Log.i(
                TAG,
                "ADB command completed on ${adbStartResult.endpoint}; waiting for Binder handshake",
            )
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            Log.i(TAG, "ADB Binder handshake received")
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            val serverInfo = connectHandshake(handshakeResult, startupLogListener)
            PrivilegeServerHandshakeRegistry.acknowledge(launchCorrelationId)
            return serverInfo
        } catch (e: PrivilegeStartupException) {
            Log.e(TAG, "ADB startup failed", e)
            startupLogListener.emitStartupLog("runtime", "ADB startup failed: ${e.message.orEmpty()}")
            val adbResult = startResult
            if (adbResult != null) {
                val serverDiagnostics = readAdbServerDiagnostics(
                    adbResult = adbResult,
                    adbManager = adbManager,
                    startupLogListener = startupLogListener,
                )
                throw PrivilegeServerLaunchUncertainException(
                    "ADB start did not complete the Privileged Server handshake on " +
                        "${adbResult.endpoint}: ${adbResult.outputText}$serverDiagnostics",
                    e,
                )
            }
            throw e
        } finally {
            PrivilegeServerHandshakeRegistry.cancel(launchCorrelationId)
        }
    }

    public fun getServerInfo(): PrivilegeServerInfo =
        requireServerConnection().serverInfo

    /**
     * Returns an inert Binder owned by the connected Privileged Server process.
     *
     * The Binder exposes no privileged operations. It can be passed to another process as a
     * lifecycle token and becomes dead when the current Privileged Server process exits. Calls
     * for the same server process return the same Binder; a replacement server returns a new one.
     * Do not retain the Binder across [serverState] changes.
     *
     * @throws priv.kit.core.binder.PrivilegeServerUnavailableException if no live Privileged
     * Server is connected.
     */
    public fun getServerLifecycleBinder(): IBinder =
        callServer { server ->
            server.getLifecycleBinder()
        } ?: serverUnavailable(cause = null)

    /**
     * Returns whether the connected privileged server is subject to permission restrictions.
     *
     * Root servers are always treated as unrestricted without making a permission Binder call.
     */
    public fun isPermissionRestricted(): Boolean {
        val connection = requireServerConnection()
        if (connection.serverInfo.uid == PRIVILEGE_INTERNAL_ROOT_UID) return false
        return callServer(connection) { server ->
            !server.canGrantRuntimePermissions()
        }
    }

    public fun checkServerPermission(permission: String): Int {
        require(permission.isNotBlank()) { "permission must not be blank" }
        return callServer { server ->
            server.checkServerPermission(permission)
        }
    }

    public fun checkPermission(
        permName: String,
        pkgName: String,
        userId: Int = currentUserId,
    ): Int {
        return callServer { server ->
            server.checkPermission(
                permName,
                pkgName,
                userId,
            )
        }
    }

    public fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int = currentUserId,
    ) {
        callServer { server ->
            server.grantRuntimePermission(
                packageName,
                permissionName,
                userId,
            )
        }
    }

    public fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int = currentUserId,
    ) {
        callServer { server ->
            server.revokeRuntimePermission(
                packageName,
                permissionName,
                userId,
            )
        }
    }

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

    public fun shutdownServer() {
        try {
            callServer { server ->
                server.shutdown()
            }
        } finally {
            clearCurrentServer()
        }
    }

    public suspend fun startUserService(spec: PrivilegeUserServiceSpec) {
        userServiceClient.start(spec)
    }

    public suspend fun bindUserService(spec: PrivilegeUserServiceSpec): PrivilegeUserServiceConnection =
        userServiceClient.bind(spec)

    public suspend fun stopUserService(spec: PrivilegeUserServiceSpec) {
        userServiceClient.stop(spec)
    }

    internal fun connectHandshake(
        handshakeResult: PrivilegeServerHandshakeResult,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo =
        connectServer(
            serverBinder = handshakeResult.serverBinder,
            serverInfo = handshakeResult.serverInfo,
            startupLogListener = startupLogListener,
        )

    internal fun initializeRuntimeConnection() {
        synchronized(runtimeConnectionListenerLock) {
            if (runtimeConnectionListener != null) return
            runtimeConnectionListener = PrivilegeServerHandshakeRegistry.addReadyListener(
                listener = ::connectReadyHandshake,
            )
        }
    }

    internal fun <T> withServerConnectionLock(block: () -> T): T =
        synchronized(serverLock) { block() }

    private fun connectReadyHandshake(handshakeResult: PrivilegeServerHandshakeResult): Boolean =
        try {
            val serverInfo = connectHandshake(
                handshakeResult = handshakeResult,
                startupLogListener = null,
            )
            val event = PrivilegeRuntimeConnectionEvent(
                serverInfo = serverInfo,
                origin = when (handshakeResult.origin) {
                    priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH ->
                        PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH
                    priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin.OWNER_RECONNECT ->
                        PrivilegeRuntimeConnectionOrigin.OWNER_RECONNECT
                },
                clientStartOperationId = handshakeResult.clientStartOperationId,
                launchCorrelationId = handshakeResult.launchCorrelationId,
            )
            mutableServerConnectionEvents.tryEmit(event)
            true
        } catch (throwable: Throwable) {
            Log.e(TAG, "Server connection handoff failed", throwable)
            false
        }

    @Throws(PrivilegeStartupException::class)
    private fun connectServer(
        serverBinder: IBinder,
        serverInfo: PrivilegeServerInfo,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val server = IPrivilegeServer.Stub.asInterface(serverBinder)
            ?: throw PrivilegeStartupException("Privileged Server returned an invalid Binder")

        if (!serverInfo.matchesCurrentRuntime()) {
            throw PrivilegeStartupException(
                "Unsupported Privileged Server protocol=${serverInfo.protocolVersion}; " +
                    "expected protocol=${PrivilegeProtocol.VERSION}",
            )
        }

        grantOwnerStartupPermissions(serverInfo, server, startupLogListener)
        return installCurrentServer(serverInfo, server)
    }

    private fun grantOwnerStartupPermissions(
        serverInfo: PrivilegeServerInfo,
        server: IPrivilegeServer,
        startupLogListener: PrivilegeStartupLogListener?,
    ) {
        val context = runCatching { PrivilegeContext.require() }.getOrNull() ?: return
        val userId = PrivilegeAndroidUsers.userIdFromUid(context.applicationInfo.uid)
        OWNER_STARTUP_PERMISSIONS.forEach { permission ->
            if (!PrivilegeManifestPermissions.isDeclared(context, permission)) {
                Log.i(TAG, "Owner startup permission not declared; skipping grant: $permission")
                return@forEach
            }
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Owner startup permission already granted: $permission")
                return@forEach
            }
            val granted = runCatching {
                grantRuntimePermissionForRuntime(
                    serverInfo = serverInfo,
                    server = server,
                    packageName = context.packageName,
                    permissionName = permission,
                    userId = userId,
                )
            }.getOrElse { throwable ->
                Log.w(TAG, "Owner startup permission grant failed: $permission", throwable)
                startupLogListener.emitStartupLog(
                    source = "runtime",
                    message = "Failed to grant owner permission $permission: ${throwable.message.orEmpty()}",
                )
                false
            }
            if (granted) {
                Log.i(TAG, "Owner startup permission granted: $permission")
                startupLogListener.emitStartupLog(
                    source = "runtime",
                    message = "Granted owner permission $permission",
                )
            } else {
                Log.i(TAG, "Server cannot grant runtime permissions; skipping grant: $permission")
                startupLogListener.emitStartupLog(
                    source = "runtime",
                    message = "Server cannot grant runtime permissions; skipped $permission",
                )
            }
        }
    }

    internal fun grantRuntimePermissionForRuntime(
        serverInfo: PrivilegeServerInfo,
        server: IPrivilegeServer,
        packageName: String,
        permissionName: String,
        userId: Int,
    ): Boolean {
        if (serverInfo.uid != PRIVILEGE_INTERNAL_ROOT_UID && !server.canGrantRuntimePermissions()) {
            return false
        }
        server.grantRuntimePermission(
            packageName,
            permissionName,
            userId,
        )
        return true
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
                serverUnavailable(cause = null)
            }

            var next: ServerConnection? = null
            val deathRecipient = IBinder.DeathRecipient {
                next?.let(::markServerDisconnected)
            }
            try {
                binder.linkToDeath(deathRecipient, 0)
            } catch (e: RemoteException) {
                serverUnavailable(e)
            }

            val newConnection = ServerConnection(
                serverInfo = serverInfo,
                server = server,
                deathRecipient = deathRecipient,
            )
            if (!binder.pingBinder()) {
                newConnection.unlink()
                serverUnavailable(cause = null)
            }
            next = newConnection
            previous = current
            currentServer = newConnection
            mutableServerState.value = serverInfo
            PrivilegeRuntimeStartCoordinator.markServerConnected()
            newConnection
        }
        previous?.unlink()
        return connection.serverInfo
    }

    private fun requireServerConnection(): ServerConnection {
        return synchronized(serverLock) {
            currentServer
        } ?: serverUnavailable(cause = null)
    }

    internal fun requireServerInterface(): IPrivilegeServer =
        requireServerConnection().server

    internal fun <T> callServer(block: (IPrivilegeServer) -> T): T =
        callServer(requireServerConnection(), block)

    private fun <T> callServer(
        connection: ServerConnection,
        block: (IPrivilegeServer) -> T,
    ): T =
        try {
            block(connection.server)
        } catch (exception: RemoteException) {
            val unavailable =
                exception is DeadObjectException ||
                    !connection.server.asBinder().isBinderAlive
            if (!unavailable) throw exception
            markServerDisconnected(connection)
            serverUnavailable(exception)
        }

    private fun getUserServiceManagerBinder(): IBinder =
        callServer { server ->
            server.getUserServiceManager()
        } ?: serverUnavailable(cause = null)

    internal fun runtimeConfig(): PrivilegeConfigSnapshot =
        PrivilegeConfig.snapshot()

    private fun markServerDisconnected(connection: ServerConnection) {
        val notify = synchronized(serverLock) {
            if (currentServer !== connection) {
                false
            } else {
                currentServer = null
                mutableServerState.value = null
                PrivilegeRuntimeStartCoordinator.markServerDisconnected()
                true
            }
        }
        if (notify) {
            connection.unlink()
        }
    }

    private fun clearCurrentServer() {
        val previous = synchronized(serverLock) {
            currentServer.also {
                currentServer = null
                if (it != null) {
                    mutableServerState.value = null
                    PrivilegeRuntimeStartCoordinator.markServerDisconnected()
                }
            }
        }
        previous?.unlink()
    }

    private fun buildAdbManager(
        adbDeviceName: String?,
    ): PrivilegeAdbManager {
        return PrivilegeAdbManager.create(
            adbDeviceName = resolveAdbDeviceName(adbDeviceName),
        )
    }

    private fun resolveAdbDeviceName(adbDeviceName: String?): String {
        val requestedName = adbDeviceName?.trim()
        if (!requestedName.isNullOrEmpty()) return requestedName

        val applicationContext = PrivilegeContext.require()
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
        return this
            ?.toPrivilegeAdbDeviceNameText()
            ?.ifBlank { null }
    }

    private suspend fun readAdbServerDiagnostics(
        adbResult: PrivilegeAdbStartResult,
        adbManager: PrivilegeAdbManager,
        startupLogListener: PrivilegeStartupLogListener?,
    ): String {
        val output = runCatching {
            adbManager.readRuntimeDiagnostics(
                endpoint = adbResult.endpoint,
                startupLogListener = startupLogListener,
            )
        }.getOrElse { throwable ->
            "[diag] Failed to fetch server diagnostics: ${throwable.javaClass.simpleName}: ${throwable.message}"
        }
        return "\n[server diagnostics]\n$output"
    }

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun IPrivilegeServer.canGrantRuntimePermissions(): Boolean =
        checkServerPermission(GRANT_RUNTIME_PERMISSIONS) ==
            PackageManager.PERMISSION_GRANTED

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

    private val OWNER_STARTUP_PERMISSIONS: Set<String> = setOf(
        Manifest.permission.WRITE_SECURE_SETTINGS,
    )

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

internal fun rootServerLaunchMayHaveCompleted(
    processIsAlive: Boolean,
    exitCode: Int?,
): Boolean = processIsAlive || exitCode == null || exitCode == 0

private suspend fun awaitRootHandshakeOrStarterExit(
    pendingHandshake: PrivilegePendingHandshake,
    rootProcess: PrivilegeRootProcess,
    timeoutMillis: Long,
): PrivilegeServerHandshakeResult = coroutineScope {
    val handshake = async { pendingHandshake.await(timeoutMillis) }
    val starterExit = async {
        runInterruptible(Dispatchers.IO) {
            rootProcess.waitForExit()
        }
    }
    try {
        select {
            handshake.onAwait { it }
            starterExit.onAwait { exitCode ->
                if (exitCode == PrivilegeStarterContract.STOP_EXISTING_SERVER_FAILED_EXIT_CODE) {
                    throw PrivilegeExistingServerStopException(
                        "Native starter could not stop the existing Privileged Server: " +
                            rootProcess.outputText(),
                    )
                }
                if (exitCode != 0) {
                    throw PrivilegeStartupException(
                        "Native starter exited with code $exitCode: ${rootProcess.outputText()}",
                    )
                }
                handshake.await()
            }
        }
    } finally {
        handshake.cancel()
        starterExit.cancel()
    }
}

internal fun cleanupRootProcessAfterStart(
    process: PrivilegeRootProcess?,
    startupCompleted: Boolean,
) {
    if (!startupCompleted) {
        runCatching { process?.destroy() }
    }
}
