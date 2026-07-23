package priv.kit.core

import android.Manifest
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import priv.kit.core.adb.PrivilegeAdbIdentity
import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbStartResult
import priv.kit.core.adb.PrivilegeAdbManager
import priv.kit.core.binder.serverControlCall
import priv.kit.core.binder.serverUnavailable
import priv.kit.core.internal.binder.IPrivilegeServer
import priv.kit.core.internal.core.PrivilegeAndroidUsers
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.internal.core.PrivilegeServerHandshakeResult
import priv.kit.core.internal.runtime.PrivilegeOwnerTokenStore
import priv.kit.core.internal.runtime.PrivilegeRootProcess
import priv.kit.core.internal.runtime.PrivilegeRootStarter
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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible

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
    ): PrivilegeServerInfo = startRootWithLaunchId(
        initialLaunchId = PrivilegeRuntimeStartCoordinator.newInitialLaunchId(),
        timeoutMillis = timeoutMillis,
        startupLogListener = startupLogListener,
    )

    internal suspend fun startRootWithLaunchId(
        initialLaunchId: String,
        timeoutMillis: Long,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val token = ownerTokenStore().readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token, initialLaunchId)
        var rootProcess: PrivilegeRootProcess? = null
        var startupCompleted = false

        try {
            startupLogListener.emitStartupLog("runtime", "Starting with root")
            rootProcess = runInterruptible {
                PrivilegeRootStarter.start(
                    buildShortNativeStarterCommand(initialLaunchId = initialLaunchId),
                    startupLogListener = startupLogListener,
                )
            }
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            val serverInfo = connectHandshake(handshakeResult, startupLogListener)
            PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)
            startupCompleted = true
            return serverInfo
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (e: PrivilegeStartupException) {
            startupLogListener.emitStartupLog("runtime", "Startup failed: ${e.message.orEmpty()}")
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
                PrivilegeServerHandshakeRegistry.cancel(initialLaunchId)
            cleanupRootProcessAfterStart(
                process = rootProcess,
                startupCompleted = startupCompleted || deliveredServerPreserved,
            )
        }
    }

    @Throws(PrivilegeStartupException::class)
    public fun createShellStartCommand(): String {
        ownerTokenStore().readOrCreate()
        return buildShortNativeStarterCommand()
    }

    internal fun createShellStartCommandWithLaunchId(initialLaunchId: String): String =
        buildShortNativeStarterCommand(initialLaunchId = initialLaunchId)

    @Throws(PrivilegeStartupException::class)
    public fun createAdbManager(
        adbDeviceName: String? = null,
    ): PrivilegeAdbManager =
        buildAdbManager(adbDeviceName = adbDeviceName)

    @Throws(PrivilegeStartupException::class)
    public fun connectReadyServer(): PrivilegeServerInfo? {
        val token = ownerTokenStore().readOrCreate()
        val handshakeResult = PrivilegeServerHandshakeRegistry.claimReady(token) ?: return null
        return connectHandshake(handshakeResult)
    }

    public val serverState: StateFlow<PrivilegeServerInfo?>
        get() {
            initializeRuntimeConnection()
            return serverStateFlow
        }

    @Throws(PrivilegeStartupException::class)
    public suspend fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo = startAdbWithLaunchId(
        initialLaunchId = PrivilegeRuntimeStartCoordinator.newInitialLaunchId(),
        options = options,
        timeoutMillis = timeoutMillis,
        adbDeviceName = adbDeviceName,
        startupLogListener = startupLogListener,
    )

    internal suspend fun startAdbWithLaunchId(
        initialLaunchId: String,
        options: PrivilegeAdbStartOptions,
        timeoutMillis: Long,
        adbDeviceName: String?,
        startupLogListener: PrivilegeStartupLogListener?,
    ): PrivilegeServerInfo {
        val token = ownerTokenStore().readOrCreate()
        val adbManager = buildAdbManager(
            adbDeviceName = adbDeviceName,
        )
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token, initialLaunchId)
        var startResult: PrivilegeAdbStartResult? = null

        try {
            Log.i(TAG, "Starting through ADB keySignature=<redacted>")
            startupLogListener.emitStartupLog("runtime", "Starting through ADB")
            val adbStartResult = adbManager.start(
                PrivilegeServerLaunchCommandBuilder.build(initialLaunchId),
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
            PrivilegeServerHandshakeRegistry.acknowledge(initialLaunchId)
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
            PrivilegeServerHandshakeRegistry.cancel(initialLaunchId)
        }
    }

    public fun getServerInfo(): PrivilegeServerInfo =
        requireServerConnection().serverInfo

    /**
     * Returns whether the connected privileged server is subject to permission restrictions.
     *
     * Root servers are always treated as unrestricted without making a permission Binder call.
     */
    public fun isPermissionRestricted(): Boolean {
        val connection = requireServerConnection()
        if (connection.serverInfo.uid == PRIVILEGE_INTERNAL_ROOT_UID) return false
        return serverControlCall {
            !connection.server.canGrantRuntimePermissions()
        }
    }

    public fun checkServerPermission(permission: String): Int {
        require(permission.isNotBlank()) { "permission must not be blank" }
        return serverControlCall {
            requireServerInterface().checkServerPermission(permission)
        }
    }

    public fun checkPermission(
        permName: String,
        pkgName: String,
        userId: Int = currentUserId,
    ): Int {
        return serverControlCall {
            requireServerInterface().checkPermission(
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
        serverControlCall {
            requireServerInterface().grantRuntimePermission(
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
            serverControlCall {
                requireServerInterface().shutdown()
            }
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
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo =
        connectServer(
            serverBinder = handshakeResult.serverBinder,
            serverInfo = handshakeResult.serverInfo,
            startupLogListener = startupLogListener,
        )

    internal fun initializeRuntimeConnection() {
        synchronized(runtimeConnectionListenerLock) {
            if (runtimeConnectionListener != null) return
            val token = ownerTokenStore().readOrCreate()
            runtimeConnectionListener = PrivilegeServerHandshakeRegistry.addReadyListener(
                token = token,
                listener = ::connectReadyHandshake,
            )
        }
    }

    internal fun <T> withServerConnectionLock(block: () -> T): T =
        synchronized(serverLock) { block() }

    private fun connectReadyHandshake(handshakeResult: PrivilegeServerHandshakeResult): Boolean =
        try {
            val serverInfo = connectHandshake(handshakeResult)
            val event = PrivilegeRuntimeConnectionEvent(
                serverInfo = serverInfo,
                origin = when (handshakeResult.origin) {
                    priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH ->
                        PrivilegeRuntimeConnectionOrigin.INITIAL_LAUNCH
                    priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin.OWNER_RECONNECT ->
                        PrivilegeRuntimeConnectionOrigin.OWNER_RECONNECT
                },
                clientStartOperationId = handshakeResult.clientStartOperationId,
                initialLaunchId = handshakeResult.initialLaunchId,
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
                serverUnavailable()
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
                serverUnavailable()
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
        } ?: serverUnavailable()
    }

    @JvmSynthetic
    internal fun requireServerInterface(): IPrivilegeServer =
        requireServerConnection().server

    private fun getUserServiceManagerBinder(): IBinder =
        serverControlCall {
            requireServerInterface().getUserServiceManager()
        } ?: serverUnavailable()

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

    internal fun buildShortNativeStarterCommand(
        initialLaunchId: String? = null,
        starterPath: String = PrivilegeServerLaunchCommandBuilder.buildNativeStarterPath(),
    ): String =
        PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
            starterPath = starterPath,
            initialLaunchId = initialLaunchId,
            clearInheritedLaunchId = false,
        )

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

    private fun ownerTokenStore(): PrivilegeOwnerTokenStore =
        PrivilegeOwnerTokenStore

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

internal fun cleanupRootProcessAfterStart(
    process: PrivilegeRootProcess?,
    startupCompleted: Boolean,
) {
    if (!startupCompleted) {
        runCatching { process?.destroy() }
    }
}
