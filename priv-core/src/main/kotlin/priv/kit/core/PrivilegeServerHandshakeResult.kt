package priv.kit.core

import android.os.IBinder

data class PrivilegeServerHandshakeResult(
    val token: String,
    val serverInfo: PrivilegeServerInfo,
    val serverBinder: IBinder,
)
