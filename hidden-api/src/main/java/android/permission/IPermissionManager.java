package android.permission;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * @noinspection unused
 */
public interface IPermissionManager extends IInterface {
    abstract class Stub extends Binder implements IPermissionManager {
        public static IPermissionManager asInterface(IBinder obj) {
            throw new RuntimeException();
        }
    }

    // 11 - 14.0.0_r28, 14.0.0_r38 - 14.0.0_r45
    void revokeRuntimePermission(String packageName, String permissionName, int userId, String reason);

    // 14.0.0_r29 - 14.0.0_r37
    void revokeRuntimePermission(String packageName, String permissionName, int deviceId, int userId, String reason);

    // 14.0.0_r50 - 17
    void revokeRuntimePermission(String packageName, String permissionName, String persistentDeviceId, int userId, String reason);
}
