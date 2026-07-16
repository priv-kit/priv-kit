package priv.kit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import priv.kit.adb.PrivilegeAdbIdentity
import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbStartResult
import priv.kit.adb.PrivilegeAdbStarter
import priv.kit.binder.serverControlCall
import priv.kit.binder.serverUnavailable
import priv.kit.internal.binder.IPrivilegeServer
import priv.kit.internal.core.PrivilegeAndroidUsers
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.internal.core.PrivilegeServerHandshakeResult
import priv.kit.internal.core.PrivilegeServerLaunchCommand
import priv.kit.internal.runtime.PrivilegeOwnerTokenStore
import priv.kit.internal.runtime.PrivilegeRootProcess
import priv.kit.internal.runtime.PrivilegeRootStarter
import priv.kit.internal.runtime.PrivilegeContext
import priv.kit.internal.runtime.PrivilegeUserServiceClient
import priv.kit.internal.runtime.PrivilegeServerLaunchCommandBuilder
import priv.kit.userservice.PrivilegeUserServiceSpec
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

public object Privilege {
    private const val DEFAULT_START_TIMEOUT_MILLIS = 15_000L
    private const val GRANT_RUNTIME_PERMISSIONS = "android.permission.GRANT_RUNTIME_PERMISSIONS"
    private const val ROOT_UID = 0
    private const val TAG = "PrivKit"

    private val serverLock = Any()
    private val currentUserId: Int by lazy {
        Process.myUserHandle().hashCode()
    }
    private var currentServer: ServerConnection? = null
    private val disconnectedListeners = CopyOnWriteArraySet<() -> Unit>()
    private val userServiceClient = PrivilegeUserServiceClient(::getUserServiceManagerBinder)

    @Throws(PrivilegeStartupException::class, InterruptedException::class)
    public fun startRoot(
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): PrivilegeServerInfo {
        val token = ownerTokenStore().readOrCreate()
        val pendingHandshake = PrivilegeServerHandshakeRegistry.prepare(token)
        var rootProcess: PrivilegeRootProcess? = null
        var startupCompleted = false

        try {
            startupLogListener.emitStartupLog("runtime", "Starting with root")
            rootProcess = PrivilegeRootStarter.start(
                buildRootCommandLine(),
                startupLogListener = startupLogListener,
            )
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            val serverInfo = connectHandshake(handshakeResult, startupLogListener)
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
            PrivilegeServerHandshakeRegistry.cancel(token)
            cleanupRootProcessAfterStart(
                process = rootProcess,
                startupCompleted = startupCompleted,
            )
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

    @Throws(PrivilegeStartupException::class, InterruptedException::class)
    public fun startAdb(
        options: PrivilegeAdbStartOptions = PrivilegeAdbStartOptions(),
        timeoutMillis: Long = DEFAULT_START_TIMEOUT_MILLIS,
        adbDeviceName: String? = null,
        startupLogListener: PrivilegeStartupLogListener? = null,
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
                "ADB command completed on ${adbStartResult.endpoint}; waiting for Binder handshake",
            )
            startupLogListener.emitStartupLog("runtime", "Waiting for Privileged Server handshake")
            val handshakeResult = pendingHandshake.await(timeoutMillis)
            Log.i(TAG, "ADB Binder handshake received")
            startupLogListener.emitStartupLog("runtime", "Privileged Server handshake received")
            return connectHandshake(handshakeResult, startupLogListener)
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
                throw PrivilegeServerLaunchUncertainException(
                    "ADB start did not complete the Privileged Server handshake on " +
                        "${adbResult.endpoint}: ${adbResult.outputText}$serverDiagnostics",
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

    public fun addServerDisconnectedListener(listener: () -> Unit): Closeable {
        disconnectedListeners += listener
        return Closeable {
            disconnectedListeners -= listener
        }
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
            if (!context.hasPermissionDeclaration(permission)) {
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
        if (serverInfo.uid != ROOT_UID && !server.canGrantRuntimePermissions()) {
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
        return PrivilegeAdbStarter.forOwnerToken(
            ownerToken = ownerToken,
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
                endpoint = adbResult.endpoint,
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

    private fun IPrivilegeServer.canGrantRuntimePermissions(): Boolean =
        checkServerPermission(GRANT_RUNTIME_PERMISSIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun Context.hasPermissionDeclaration(permission: String): Boolean =
        runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_PERMISSIONS,
                )
            }
            packageInfo.requestedPermissions?.contains(permission) == true
        }.getOrDefault(false)

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
