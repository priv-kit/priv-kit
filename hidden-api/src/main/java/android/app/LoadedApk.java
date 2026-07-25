package android.app;

import android.content.pm.ApplicationInfo;

/**
 * @noinspection unused
 */
public class LoadedApk {
    public String getPackageName() {
        throw new RuntimeException();
    }

    public ApplicationInfo getApplicationInfo() {
        throw new RuntimeException();
    }

    public Application makeApplication(boolean forceDefaultAppClass, Instrumentation instrumentation) {
        throw new RuntimeException();
    }
}
