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
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerInfo
import java.util.concurrent.ConcurrentHashMap

class PrivilegeHandshakeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

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
        val accepted = if (extras != null && token == arg && token == ownerToken()) {
            PrivilegeServerHandshakeRegistry.deliverReady(
                token = token,
                serverBinder = serverBinder,
                serverInfo = extras.toServerInfo(),
            )
        } else {
            false
        }
        Log.i(TAG, "Handshake call accepted=$accepted")

        return Bundle().apply {
            putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, accepted)
            if (accepted && token != null) {
                putBinder(
                    PrivilegeHandshakeContract.RESULT_OWNER_BINDER,
                    ownerBinders.getOrPut(token) { Binder() },
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

    companion object {
        private const val TAG = "PrivKitRuntime"
        private val ownerBinders = ConcurrentHashMap<String, IBinder>()
    }

    private fun ownerToken(): String? =
        runCatching {
            context?.let { PrivilegeOwnerTokenStore(it).readIfExists() }
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to read owner token for handshake validation", throwable)
            null
        }

    private fun Bundle.toServerInfo(): PrivilegeServerInfo =
        PrivilegeServerInfo(
            uid = getInt(PrivilegeHandshakeContract.EXTRA_UID),
            pid = getInt(PrivilegeHandshakeContract.EXTRA_PID),
            mode = getInt(PrivilegeHandshakeContract.EXTRA_MODE),
            protocolVersion = getInt(
                PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION,
            ),
            serverVersion = getString(
                PrivilegeHandshakeContract.EXTRA_SERVER_VERSION,
                "",
            ),
        )
}
