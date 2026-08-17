package priv.kit.core.internal.core

import android.os.IBinder

internal data class PrivilegeServerServiceEndpoints(
    val fileSystemBinder: IBinder,
    val userServiceManagerBinder: IBinder,
)
