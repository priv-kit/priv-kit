package priv.kit.internal.binder;

interface IPrivilegeServer {
    void shutdown();
    IBinder getUserServiceManager();
    boolean hasSystemService(String serviceName);
    int checkPermission(String permission);
}
