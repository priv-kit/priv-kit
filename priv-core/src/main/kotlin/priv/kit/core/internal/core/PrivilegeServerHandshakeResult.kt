package priv.kit.core.internal.core

import android.os.IBinder
import priv.kit.core.PrivilegeServerInfo

internal enum class PrivilegeServerHandshakeOrigin {
    INITIAL_LAUNCH,
    OWNER_RECONNECT,
}

internal data class PrivilegeServerHandshakeResult(
    val serverInfo: PrivilegeServerInfo,
    val serverBinder: IBinder,
    val origin: PrivilegeServerHandshakeOrigin = PrivilegeServerHandshakeOrigin.INITIAL_LAUNCH,
    val launchCorrelationId: String? = null,
    val clientStartOperationId: Long? = null,
    val deliverySerial: Long = 0L,
)
