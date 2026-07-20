package priv.kit.internal.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import priv.kit.Privilege
import priv.kit.PrivilegeServerInfo
import priv.kit.internal.core.PrivilegeAndroidUsers
import priv.kit.internal.core.PrivilegeHandshakeContract
import priv.kit.internal.core.PrivilegeProtocol
import priv.kit.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.internal.core.PrivilegeServerHandshakeOrigin
import priv.kit.internal.userservice.PrivilegeUserServiceContract
import priv.kit.internal.userservice.PrivilegeUserServiceHandshakeRegistry
import java.util.concurrent.ConcurrentHashMap

internal class PrivilegeHandshakeProvider public constructor() : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { providerContext ->
            PrivilegeContext.install(providerContext)
            Privilege.initializeRuntimeConnection()
            PrivilegeRuntimeStartCoordinator.markOwnerProcessStarted()
            PrivilegeOwnerProcessNotifier.schedule(providerContext)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method == PrivilegeUserServiceContract.METHOD_USER_SERVICE_READY) {
            return handleUserServiceReady(arg, extras)
        }
        if (method == PrivilegeUserServiceContract.METHOD_USER_SERVICE_CLAIM) {
            return handleUserServiceClaim(arg, extras)
        }
        if (method != PrivilegeHandshakeContract.METHOD_SERVER_READY) {
            return super.call(method, arg, extras)
        }

        return handleServerReady(arg, extras)
    }

    private fun handleServerReady(arg: String?, extras: Bundle?): Bundle {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val token = extras?.getString(PrivilegeHandshakeContract.EXTRA_TOKEN)?.takeIf { it.isNotBlank() }
        val initialLaunchId = extras
            ?.getString(PrivilegeHandshakeContract.EXTRA_INITIAL_LAUNCH_ID)
            ?.takeIf { token == null && it.isNotBlank() }
        val serverBinder = extras?.getBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER)
        Log.i(
            TAG,
            "Handshake call received initial=${token == null}, tokenMatches=${token == arg}, " +
                "callingUid=$callingUid, callingPid=$callingPid, hasExtras=${extras != null}, " +
                "hasBinder=${serverBinder != null}",
        )
        val serverInfo = extras?.toServerInfo(
            uid = callingUid,
            pid = callingPid,
        )
        val protocolMatches = serverInfo?.matchesCurrentProtocol() == true
        val classpathIdentityMatches = extras?.classpathIdentityMatches() == true
        val matchesCurrentRuntime = protocolMatches && classpathIdentityMatches
        val trustedCaller = isTrustedServerStarterCaller(callingUid)
        val acceptedToken = acceptedToken(
            token = token,
            arg = arg,
            trustedCaller = trustedCaller,
            matchesCurrentRuntime = matchesCurrentRuntime,
        )
        val origin = if (token == null) {
            PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH
        } else {
            PrivilegeServerHandshakeOrigin.OWNER_RECONNECT
        }
        if (serverInfo != null && trustedCaller && !matchesCurrentRuntime) {
            Log.w(
                TAG,
                "Rejecting server mismatch protocol=${serverInfo.protocolVersion}, " +
                    "classpathIdentityMatches=$classpathIdentityMatches",
            )
        }
        val accepted = if (acceptedToken != null && matchesCurrentRuntime) {
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = acceptedToken,
                serverBinder = serverBinder,
                serverInfo = serverInfo,
                origin = origin,
                initialLaunchId = initialLaunchId,
            )
        } else {
            false
        }
        val replacementCommand = replacementCommandFor(
            token = token,
            acceptedToken = acceptedToken,
            trustedCaller = trustedCaller,
            serverInfo = serverInfo,
            matchesCurrentRuntime = matchesCurrentRuntime,
            initialLaunchId = initialLaunchId,
        )
        Log.i(TAG, "Handshake call accepted=$accepted")

        return Bundle().apply {
            putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, accepted)
            if (accepted) {
                putBinder(
                    PrivilegeHandshakeContract.RESULT_OWNER_BINDER,
                    ownerBinders.getOrPut(acceptedToken) { Binder() },
                )
                if (token == null) {
                    val runtimeConfig = Privilege.runtimeConfig()
                    putString(PrivilegeHandshakeContract.RESULT_TOKEN, acceptedToken)
                    putLong(
                        PrivilegeHandshakeContract.EXTRA_FOLLOW_DEATH_DELAY_MILLIS,
                        runtimeConfig.followDeathDelayMillis,
                    )
                    putBoolean(
                        PrivilegeHandshakeContract.EXTRA_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                        runtimeConfig.activeReconnectOnOwnerDeath,
                    )
                }
            } else if (replacementCommand != null) {
                putString(PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND, replacementCommand)
            }
        }
    }

    private fun handleUserServiceReady(
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val token = extras?.getString(PrivilegeUserServiceContract.EXTRA_TOKEN)
        val processBinder = extras?.getBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER)
        val accepted = token == arg &&
            PrivilegeUserServiceHandshakeRegistry.deliverReady(
                token = token,
                processBinder = processBinder,
            )
        Log.i(TAG, "UserService ready received accepted=$accepted")
        return Bundle().apply {
            putBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, accepted)
        }
    }

    private fun handleUserServiceClaim(
        arg: String?,
        extras: Bundle?,
    ): Bundle {
        val token = extras?.getString(PrivilegeUserServiceContract.EXTRA_TOKEN) ?: arg
        val ready = PrivilegeUserServiceHandshakeRegistry.claimReady(token)
        Log.i(TAG, "UserService claim tokenPresent=${!token.isNullOrBlank()} ready=${ready != null}")
        return Bundle().apply {
            putBoolean(PrivilegeUserServiceContract.KEY_SUCCESS, ready != null)
            if (ready != null) {
                putBinder(PrivilegeUserServiceContract.EXTRA_PROCESS_BINDER, ready)
            }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun ownerToken(): String? =
        runCatching {
            PrivilegeOwnerTokenStore.readIfExists()
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to read owner token for handshake validation", throwable)
            null
        }

    private fun ownerTokenOrCreate(): String? =
        runCatching {
            PrivilegeOwnerTokenStore.readOrCreate()
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to create owner token for initial server handshake", throwable)
            null
        }

    private fun acceptedToken(
        token: String?,
        arg: String?,
        trustedCaller: Boolean,
        matchesCurrentRuntime: Boolean,
    ): String? {
        if (token != null) {
            return if (token == arg && token == ownerToken()) token else null
        }
        return if (trustedCaller && matchesCurrentRuntime) {
            ownerTokenOrCreate()
        } else {
            null
        }
    }

    private fun PrivilegeServerInfo.matchesCurrentProtocol(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun replacementCommandFor(
        token: String?,
        acceptedToken: String?,
        trustedCaller: Boolean,
        serverInfo: PrivilegeServerInfo?,
        matchesCurrentRuntime: Boolean,
        initialLaunchId: String?,
    ): String? {
        if (serverInfo == null || matchesCurrentRuntime) {
            return null
        }
        val trustedExistingServer = if (token == null) {
            trustedCaller
        } else {
            acceptedToken != null
        }
        if (!trustedExistingServer) {
            return null
        }
        Log.i(TAG, "Returning replacement starter command for stale Privileged Server")
        return PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
            initialLaunchId = initialLaunchId,
            clearInheritedLaunchId = initialLaunchId == null,
        )
    }

    private fun Bundle.classpathIdentityMatches(): Boolean {
        val expected = PrivilegeHandshakeContract.classpathIdentity(
            PrivilegeServerLaunchCommandBuilder.buildClasspath(),
        )
        return getString(PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY) == expected
    }

    private fun isTrustedServerStarterCaller(callingUid: Int): Boolean {
        val ownerUid = PrivilegeContext.require().applicationInfo.uid
        return callingUid == PrivilegeAndroidUsers.ROOT_UID ||
            callingUid == PrivilegeAndroidUsers.SYSTEM_UID ||
            callingUid == PrivilegeAndroidUsers.SHELL_UID ||
            ownerUid == callingUid
    }

    private fun Bundle.toServerInfo(
        uid: Int,
        pid: Int,
    ): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = uid,
            pid = pid,
            protocolVersion = requireIntExtra(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION),
        )

    private fun Bundle.requireIntExtra(key: String): Int {
        require(containsKey(key)) { "Handshake request is missing $key" }
        return getInt(key)
    }

    private companion object {
        private const val TAG = "PrivKit"
        private val ownerBinders = ConcurrentHashMap<String, IBinder>()
    }
}
