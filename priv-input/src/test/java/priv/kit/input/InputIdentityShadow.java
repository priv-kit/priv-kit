package priv.kit.input;

import android.os.Binder;
import android.os.Process;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowBinder;

// ShadowBinder does not implement these native methods. Model their identity
// transition so tests detect missing clear/restore calls and incorrect tokens.
@Implements(Binder.class)
public class InputIdentityShadow extends ShadowBinder {
    public static int clears;
    public static int restores;

    @Implementation
    protected static long clearCallingIdentity() {
        long token = ((long) Binder.getCallingUid() << 32) | (Binder.getCallingPid() & 0xffffffffL);
        setCallingUid(Process.myUid());
        setCallingPid(Process.myPid());
        clears++;
        return token;
    }

    @Implementation
    protected static void restoreCallingIdentity(long token) {
        setCallingUid((int) (token >>> 32));
        setCallingPid((int) token);
        restores++;
    }

    @Resetter
    public static void resetCounts() {
        clears = 0;
        restores = 0;
    }
}
