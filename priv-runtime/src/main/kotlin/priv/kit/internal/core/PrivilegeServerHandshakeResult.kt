package priv.kit.internal.core

import android.os.IBinder
import priv.kit.PrivilegeServerInfo

internal data class PrivilegeServerHandshakeResult(
    val serverInfo: PrivilegeServerInfo,
    val serverBinder: IBinder,
)
