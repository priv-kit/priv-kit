package priv.kit.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeLaunchMode
import priv.kit.core.PrivilegeProtocol
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerInfo
import java.util.concurrent.ConcurrentHashMap

class PrivilegeHandshakeProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(PrivilegeRuntimeContext::install)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PrivilegeHandshakeContract.METHOD_SERVER_READY) {
            return super.call(method, arg, extras)
        }

        val token = extras?.getString(PrivilegeHandshakeContract.EXTRA_TOKEN)
        val serverBinder = extras?.getBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER)
        Log.i(
            TAG,
            "Handshake call received tokenMatches=${token == arg}, hasExtras=${extras != null}, " +
                "hasBinder=${serverBinder != null}",
        )
        val tokenAccepted = extras != null && token == arg && token == ownerToken()
        val serverInfo = if (tokenAccepted) {
            requireNotNull(extras).toServerInfo()
        } else {
            null
        }
        val restartCommandLine = if (
            tokenAccepted &&
            token != null &&
            serverInfo != null &&
            !serverInfo.matchesCurrentRuntime()
        ) {
            buildRestartCommandLine(token, serverInfo)
        } else {
            null
        }
        val shouldShutdown = if (tokenAccepted && serverInfo != null && !serverInfo.matchesCurrentRuntime()) {
            Log.w(
                TAG,
                "Rejecting server version mismatch protocol=${serverInfo.protocolVersion}, " +
                    "serverVersion=${serverInfo.serverVersion}",
            )
            true
        } else {
            false
        }
        val accepted = if (tokenAccepted && !shouldShutdown && serverInfo != null) {
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = serverBinder,
                serverInfo = serverInfo,
            )
        } else {
            false
        }
        Log.i(TAG, "Handshake call accepted=$accepted, shouldShutdown=$shouldShutdown")

        return Bundle().apply {
            putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, accepted)
            putBoolean(PrivilegeHandshakeContract.RESULT_SHOULD_SHUTDOWN, shouldShutdown)
            restartCommandLine?.let {
                putString(PrivilegeHandshakeContract.RESULT_RESTART_COMMAND_LINE, it)
            }
            if (accepted && token != null) {
                putBinder(
                    PrivilegeHandshakeContract.RESULT_OWNER_BINDER,
                    ownerBinders.getOrPut(token) { Binder() },
                )
                val ownerDeathConfig = ownerDeathConfig()
                putLong(
                    PrivilegeHandshakeContract.RESULT_FOLLOW_DEATH_DELAY_MILLIS,
                    ownerDeathConfig.followDeathDelayMillis,
                )
                putBoolean(
                    PrivilegeHandshakeContract.RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                    ownerDeathConfig.activeReconnectOnOwnerDeath,
                )
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
            context?.let { PrivilegeOwnerTokenStore(it).readIfExists() }
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to read owner token for handshake validation", throwable)
            null
        }

    private fun ownerDeathConfig(): PrivilegeOwnerDeathConfig =
        runCatching {
            context?.let { PrivilegeOwnerDeathConfigStore(it).read() }
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to read owner death config for handshake response", throwable)
            null
        } ?: PrivilegeOwnerDeathConfig()

    private fun PrivilegeServerInfo.matchesCurrentRuntime(): Boolean =
        protocolVersion == PrivilegeProtocol.VERSION &&
            serverVersion == PrivilegeProtocol.SERVER_VERSION

    private fun buildRestartCommandLine(
        token: String,
        serverInfo: PrivilegeServerInfo,
    ): String? {
        val context = context ?: return null
        val ownerDeathConfig = ownerDeathConfig()
        return PrivilegeServerLaunchCommandBuilder.build(
            context = context,
            token = token,
            launchMode = serverInfo.toPrivilegeLaunchMode(),
            followDeathDelayMillis = ownerDeathConfig.followDeathDelayMillis,
            activeReconnectOnOwnerDeath = ownerDeathConfig.activeReconnectOnOwnerDeath,
        ).detachedCommandLine
    }

    private fun PrivilegeServerInfo.toPrivilegeLaunchMode(): PrivilegeLaunchMode =
        if (launchMode == PrivilegeLaunchMode.ROOT.value) {
            PrivilegeLaunchMode.ROOT
        } else {
            PrivilegeLaunchMode.SHELL
        }

    private fun Bundle.toServerInfo(): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = requireIntExtra(PrivilegeHandshakeContract.EXTRA_UID),
            pid = requireIntExtra(PrivilegeHandshakeContract.EXTRA_PID),
            launchMode = requireIntExtra(PrivilegeHandshakeContract.EXTRA_LAUNCH_MODE),
            protocolVersion = requireIntExtra(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION),
            serverVersion = requireStringExtra(PrivilegeHandshakeContract.EXTRA_SERVER_VERSION),
        )

    private fun Bundle.requireIntExtra(key: String): Int {
        require(containsKey(key)) { "Handshake request is missing $key" }
        return getInt(key)
    }

    private fun Bundle.requireStringExtra(key: String): String =
        requireNotNull(getString(key)?.takeIf { it.isNotBlank() }) {
            "Handshake request is missing $key"
        }

    companion object {
        private const val TAG = "PrivKitRuntime"
        private val ownerBinders = ConcurrentHashMap<String, IBinder>()
    }
}
