package priv.kit.core

import android.os.IBinder

public data class PrivilegeServerHandshakeResult public constructor(
    public val token: String,
    public val serverInfo: PrivilegeServerInfo,
    public val serverBinder: IBinder,
)
