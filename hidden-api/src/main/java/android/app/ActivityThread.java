package android.app;

import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;

/**
 * @noinspection unused
 */
public final class ActivityThread {
    public static ActivityThread currentActivityThread() {
        throw new RuntimeException();
    }

    public static ActivityThread systemMain() {
        throw new RuntimeException();
    }

    public ContextImpl getSystemContext() {
        throw new RuntimeException();
    }

    public LoadedApk getPackageInfoNoCheck(ApplicationInfo applicationInfo, CompatibilityInfo compatibilityInfo) {
        throw new RuntimeException();
    }
}
