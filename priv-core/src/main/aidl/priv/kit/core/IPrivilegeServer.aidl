package priv.kit.core;

interface IPrivilegeServer {
    int getUid();
    int getPid();
    int getMode();
    int getProtocolVersion();
    String getServerVersion();
    void updateOwnerDeathConfig(long followDeathDelayMillis, boolean activeReconnectOnOwnerDeath);
    void shutdown();
}
