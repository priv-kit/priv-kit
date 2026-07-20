package priv.kit.core.internal.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import priv.kit.core.internal.core.PrivilegeHandshakeContract

internal object PrivilegeOwnerProcessNotifier {
    fun schedule(
        context: Context,
        post: ((() -> Unit) -> Unit) = { block ->
            Handler(Looper.getMainLooper()).post { block() }
        },
        notifyChange: (Context, Uri, Int) -> Unit = ::notifyChange,
    ) {
        val applicationContext = context.applicationContext
        val uri = PrivilegeHandshakeContract.ownerProcessStartedUri(applicationContext.packageName)
        post {
            runCatching {
                notifyChange(applicationContext, uri, notificationFlags())
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to notify Privileged Server that owner process started", throwable)
            }
        }
    }

    private fun notifyChange(
        context: Context,
        uri: Uri,
        flags: Int,
    ) {
        context.contentResolver.notifyChange(uri, null, flags)
    }

    private fun notificationFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            NOTIFY_NO_DELAY
        } else {
            0
        }

    private const val NOTIFY_NO_DELAY: Int = 1 shl 15
    private const val TAG: String = "PrivKit"
}
