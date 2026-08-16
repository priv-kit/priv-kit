package priv.kit.core

import android.os.IBinder

/**
 * Identifies one connected Privileged Server process.
 *
 * [lifecycleBinder] exposes no operations. Its identity is stable for this server process and it
 * becomes dead when that process exits. Use the binder from the latest [Privilege.serverState]
 * snapshot instead of retaining it across connection changes.
 */
public class PrivilegeServerInfo internal constructor(
    public val uid: Int,
    public val pid: Int,
    public val protocolVersion: Int,
    public val lifecycleBinder: IBinder,
)
