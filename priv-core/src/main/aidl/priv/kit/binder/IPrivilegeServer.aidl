package priv.kit.binder;

interface IPrivilegeServer {
    int getUid();
    int getPid();
    int getLaunchMode();
    int getProtocolVersion();
    String getServerVersion();
    void updateOwnerDeathConfig(long followDeathDelayMillis, boolean activeReconnectOnOwnerDeath);
    void shutdown();
    void registerBinderEndpoint(IBinder binder);
    IBinder getBinderEndpoint();
    boolean unregisterBinderEndpoint();
    IBinder getUserServiceManager();
}
