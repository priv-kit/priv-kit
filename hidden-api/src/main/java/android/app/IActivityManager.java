package android.app;

import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;

import androidx.annotation.DeprecatedSinceApi;
import androidx.annotation.RequiresApi;

/**
 * @noinspection unused
 */
public interface IActivityManager extends IInterface {
    @DeprecatedSinceApi(api = Build.VERSION_CODES.Q)
    ContentProviderHolder getContentProviderExternal(String name, int userId, IBinder token);

    @RequiresApi(Build.VERSION_CODES.Q)
    ContentProviderHolder getContentProviderExternal(String name, int userId, IBinder token, String tag);

    void removeContentProviderExternal(String name, IBinder token);

    abstract class Stub extends Binder implements IActivityManager {
        public static IActivityManager asInterface(IBinder obj) {
            throw new RuntimeException();
        }
    }
}
