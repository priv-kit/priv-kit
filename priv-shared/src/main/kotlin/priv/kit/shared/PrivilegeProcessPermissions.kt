package priv.kit.shared

import android.app.IActivityManager
import android.os.ServiceManager

/** Live PID/UID permission checks without Context's process-local permission cache. */
public object PrivilegeProcessPermissions {
    public fun check(permission: String, pid: Int, uid: Int): Int {
        val binder = checkNotNull(ServiceManager.getService("activity")) {
            "ActivityManager service is unavailable"
        }
        // Context.checkPermission() can return a process-cached result. Vendor shell restriction
        // switches (e.g. Xiaomi's USB debugging security setting) may change permission checks
        // without invalidating that cache. Call ActivityManager directly so both single checks
        // and denied-permission lists reflect the current policy without restarting the server.
        return IActivityManager.Stub.asInterface(binder).checkPermission(permission, pid, uid)
    }
}
