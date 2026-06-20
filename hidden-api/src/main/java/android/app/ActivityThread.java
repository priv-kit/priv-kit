package android.app;

/**
 * @noinspection unused
 */
public final class ActivityThread {
    private ActivityThread() {
    }

    public static ActivityThread currentActivityThread() {
        throw new RuntimeException();
    }

    public static ActivityThread systemMain() {
        throw new RuntimeException();
    }
}
