package android.os;

/**
 * @noinspection unused
 */
public final class ServiceManager {
    private ServiceManager() {
    }

    public static IBinder getService(String name) {
        throw new RuntimeException();
    }
}
