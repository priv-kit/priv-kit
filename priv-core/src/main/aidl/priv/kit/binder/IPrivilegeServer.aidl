package priv.kit.binder;

interface IPrivilegeServer {
    void shutdown();
    void registerBinderEndpoint(IBinder binder);
    IBinder getBinderEndpoint();
    boolean unregisterBinderEndpoint();
    IBinder getUserServiceManager();
}
