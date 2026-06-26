package priv.kit.binder;

interface IPrivilegeServer {
    void shutdown();
    IBinder getUserServiceManager();
    boolean hasSystemService(String serviceName);
}
