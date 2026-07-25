package android.content;

import android.os.Build;
import android.os.UserHandle;

import androidx.annotation.RequiresApi;

import li.songe.remap.RemapType;

/**
 * @noinspection unused
 */
@RemapType(Context.class)
public abstract class ContextHidden {
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static int DEVICE_ID_DEFAULT;

    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user){
        throw new RuntimeException();
    }
}
