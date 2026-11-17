package rikka.shizuku

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import moe.shizuku.api.BinderContainer
import priv.kit.external.shizuku.internal.ShizukuClient
import priv.kit.external.shizuku.internal.SuiBinderDiscovery

/** Framework entry point for official Shizuku's Binder delivery. */
public class ShizukuProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val packageName = requireNotNull(context).packageName
        SuiBinderDiscovery.findBinder()?.let { binder ->
            ShizukuClient.receiveBinder(binder, packageName, isSui = true)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "sendBinder" || extras == null) return null
        val callingUid = Binder.getCallingUid()
        if (callingUid != 0 && callingUid != 2000) {
            throw SecurityException("Shizuku Binder delivery requires root or shell")
        }
        val packageName = requireNotNull(context).packageName
        extras.classLoader = BinderContainer::class.java.classLoader
        @Suppress("DEPRECATION")
        val container = extras.getParcelable<BinderContainer>(EXTRA_BINDER) ?: return null
        val binder = container.binder ?: return null

        // Attach as the host application even though Shizuku called the provider.
        val identity = Binder.clearCallingIdentity()
        try {
            ShizukuClient.receiveBinder(binder, packageName, isSui = false)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
        return Bundle()
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
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val EXTRA_BINDER = "moe.shizuku.privileged.api.intent.extra.BINDER"
    }
}
