package android.content;

import android.os.UserHandle;

import li.songe.remap.RemapType;

/**
 * @noinspection unused
 */
@RemapType(Context.class)
public abstract class ContextHidden {
    public Context createPackageContextAsUser(String packageName, int flags, UserHandle user){
        throw new RuntimeException();
    }
}
