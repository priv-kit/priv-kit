package priv.kit.core

import android.os.IBinder

/**
 * Identifies one connected Privileged Server process.
 *
 * [selinuxContext] is captured once in the server process and reported during the connection
 * handshake. It is an opaque diagnostic value and may be `null` when the platform cannot provide
 * it. Do not use it as an authorization decision.
 *
 * [lifecycleBinder] exposes no operations. Its identity is stable for this server process and it
 * becomes dead when that process exits. Use the binder from the latest [Privilege.serverState]
 * snapshot instead of retaining it across connection changes.
 */
@ConsistentCopyVisibility
public data class PrivilegeServerInfo internal constructor(
    public val uid: Int,
    public val pid: Int,
    public val protocolVersion: Int,
    public val lifecycleBinder: IBinder,
    public val selinuxContext: String? = null,
)
