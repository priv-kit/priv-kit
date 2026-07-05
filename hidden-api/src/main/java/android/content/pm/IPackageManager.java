package android.content.pm;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;

/**
 * @noinspection unused
 */
public interface IPackageManager extends IInterface {
    int checkPermission(String permName, String pkgName, int userId);

    void grantRuntimePermission(String packageName, String permissionName, int userId);

    abstract class Stub extends Binder implements IPackageManager {
        public static IPackageManager asInterface(IBinder obj) {
            throw new RuntimeException();
        }
    }
}
