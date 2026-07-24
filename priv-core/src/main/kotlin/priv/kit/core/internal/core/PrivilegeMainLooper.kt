package priv.kit.core.internal.core

import android.os.Looper

@Suppress("DEPRECATION")
internal fun preparePrivilegeMainLooper() {
    if (Looper.myLooper() == null) {
        Looper.prepareMainLooper()
    }
}
