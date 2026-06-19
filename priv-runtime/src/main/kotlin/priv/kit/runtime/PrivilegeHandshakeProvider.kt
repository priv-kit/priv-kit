package priv.kit.runtime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import priv.kit.core.PrivilegeHandshakeContract
import priv.kit.core.PrivilegeServerHandshakeRegistry
import priv.kit.core.PrivilegeServerInfo

class PrivilegeHandshakeProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != PrivilegeHandshakeContract.METHOD_SERVER_READY) {
            return super.call(method, arg, extras)
        }

        val token = extras?.getString(PrivilegeHandshakeContract.EXTRA_TOKEN)
        val serverBinder = extras?.getBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER)
        val accepted = if (extras != null && token == arg) {
            PrivilegeServerHandshakeRegistry.deliver(
                token = token,
                serverBinder = serverBinder,
                serverInfo = PrivilegeServerInfo(
                    uid = extras.getInt(PrivilegeHandshakeContract.EXTRA_UID),
                    pid = extras.getInt(PrivilegeHandshakeContract.EXTRA_PID),
                    mode = extras.getInt(PrivilegeHandshakeContract.EXTRA_MODE),
                    protocolVersion = extras.getInt(
                        PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION,
                    ),
                    serverVersion = extras.getString(
                        PrivilegeHandshakeContract.EXTRA_SERVER_VERSION,
                        "",
                    ),
                ),
            )
        } else {
            false
        }

        return Bundle().apply {
            putBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, accepted)
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
}
