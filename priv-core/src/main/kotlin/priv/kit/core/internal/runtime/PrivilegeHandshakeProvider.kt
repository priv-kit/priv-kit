package priv.kit.core.internal.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeServerInfo
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import priv.kit.core.internal.core.PrivilegeProtocol
import priv.kit.core.internal.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.internal.core.PrivilegeServerHandshakeOrigin
import priv.kit.core.internal.userservice.PrivilegeUserServiceContract
import priv.kit.core.internal.userservice.PrivilegeUserServiceHandshakeRegistry
import priv.kit.shared.PRIVILEGE_INTERNAL_ROOT_UID
import priv.kit.shared.PRIVILEGE_INTERNAL_SHELL_UID

internal class PrivilegeHandshakeProvider : ContentProvider() {
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

        return handleServerReady(extras)
    }

    private fun handleServerReady(extras: Bundle?): Bundle {
        val callingUid = Binder.getCallingUid()
        val callingPid = Binder.getCallingPid()
        val ownerReconnect = extras?.getBoolean(
            PrivilegeHandshakeContract.EXTRA_OWNER_RECONNECT,
            false,
        ) == true
        val launchCorrelationId = extras
            ?.getString(PrivilegeHandshakeContract.EXTRA_LAUNCH_CORRELATION_ID)
            ?.takeIf { !ownerReconnect && it.isNotBlank() }
        val serverBinder = extras?.getBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER)
        Log.i(
            TAG,
            "Handshake call received initial=${!ownerReconnect}, " +
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
        val origin = if (ownerReconnect) {
            PrivilegeServerHandshakeOrigin.OWNER_RECONNECT
        } else {
            PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH
        }
        if (serverInfo != null && trustedCaller && !matchesCurrentRuntime) {
            Log.w(
                TAG,
                "Rejecting server mismatch protocol=${serverInfo.protocolVersion}, " +
                    "classpathIdentityMatches=$classpathIdentityMatches",
            )
        }
        val accepted = if (trustedCaller && matchesCurrentRuntime) {
            PrivilegeServerHandshakeRegistry.deliverReady(
                serverBinder = serverBinder,
                serverInfo = serverInfo,
                origin = origin,
                launchCorrelationId = launchCorrelationId,
            )
        } else {
            false
        }
        val replacementCommand = replacementCommandFor(
            trustedCaller = trustedCaller,
            serverInfo = serverInfo,
            matchesCurrentRuntime = matchesCurrentRuntime,
            launchCorrelationId = launchCorrelationId,
        )
        Log.i(TAG, "Handshake call accepted=$accepted")

        return Bundle().apply {
            putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, accepted)
            if (accepted) {
                putBinder(
                    PrivilegeHandshakeContract.RESULT_OWNER_BINDER,
                    ownerBinder,
                )
                if (!ownerReconnect) {
                    val runtimeConfig = Privilege.runtimeConfig()
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

    private fun PrivilegeServerInfo.matchesCurrentProtocol(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION

    private fun replacementCommandFor(
        trustedCaller: Boolean,
        serverInfo: PrivilegeServerInfo?,
        matchesCurrentRuntime: Boolean,
        launchCorrelationId: String?,
    ): String? {
        if (serverInfo == null || matchesCurrentRuntime) {
            return null
        }
        if (!trustedCaller) {
            return null
        }
        Log.i(TAG, "Returning replacement starter command for stale Privileged Server")
        return PrivilegeServerLaunchCommandBuilder.buildNativeStarterCommand(
            launchCorrelationId = launchCorrelationId,
            clearInheritedLaunchCorrelationId = launchCorrelationId == null,
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
        return callingUid == PRIVILEGE_INTERNAL_ROOT_UID ||
            callingUid == Process.SYSTEM_UID ||
            callingUid == PRIVILEGE_INTERNAL_SHELL_UID ||
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
        private val ownerBinder: IBinder = Binder()
    }
}
