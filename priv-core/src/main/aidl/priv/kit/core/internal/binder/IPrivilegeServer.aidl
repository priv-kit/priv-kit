package priv.kit.core.internal.binder;

interface IPrivilegeServer {
    void shutdown();
    boolean hasSystemService(String serviceName);
    String[] getDeniedServerPermissions();
    int checkPermission(String permName, String pkgName, int userId);
    void grantRuntimePermission(String packageName, String permissionName, int userId);
    void revokeRuntimePermission(String packageName, String permissionName, int userId);
    void updateRuntimeConfig(long followDeathDelayMillis, boolean activeReconnectOnOwnerDeath);
    void prepareOwnerRestart(long passiveReconnectTimeoutMillis);
}
