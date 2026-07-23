package priv.kit.core.internal.core

import android.app.IActivityManager
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log

internal object PrivilegeContentProviderCall {
    fun call(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
        userId: Int,
        logTag: String?,
    ): Bundle? {
        val activityManager = activityManager()
        val providerToken = Binder()
        logTag?.let { Log.i(it, "Requesting content provider authority=$authority") }
        val holder = getContentProviderExternal(
            activityManager = activityManager,
            authority = authority,
            userId = userId,
            token = providerToken,
        ) ?: throw IllegalStateException("Content provider not found: $authority")

        return try {
            val provider = holder.provider
                ?: throw IllegalStateException("Content provider holder has no provider")
            logTag?.let { Log.i(it, "Content provider acquired providerClass=${provider.javaClass.name}") }
            logTag?.let { Log.i(it, "Invoking provider.call signatureArgs=4") }
            provider.call(callingPackageName(), method, arg, extras)
        } finally {
            logTag?.let { Log.i(it, "Releasing content provider authority=$authority") }
            releaseContentProviderExternal(
                activityManager = activityManager,
                authority = authority,
                token = providerToken,
                logTag = logTag,
            )
        }
    }

    private fun activityManager(): IActivityManager =
        IActivityManager.Stub.asInterface(ServiceManager.getService(Context.ACTIVITY_SERVICE))

    private fun getContentProviderExternal(
        activityManager: IActivityManager,
        authority: String,
        userId: Int,
        token: IBinder,
    ) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        activityManager.getContentProviderExternal(authority, userId, token, authority)
    } else {
        activityManager.getContentProviderExternal(authority, userId, token)
    }

    private fun releaseContentProviderExternal(
        activityManager: IActivityManager,
        authority: String,
        token: IBinder,
        logTag: String?,
    ) {
        runCatching {
            activityManager.removeContentProviderExternal(authority, token)
        }.onFailure { throwable ->
            logTag?.let { Log.w(it, "Failed to release content provider authority=$authority", throwable) }
        }
    }

    private fun callingPackageName(): String? =
        if (Process.myUid() == Process.SHELL_UID) SHELL_PACKAGE_NAME else null

    private const val SHELL_PACKAGE_NAME = "com.android.shell"
}
