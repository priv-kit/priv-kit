package priv.kit.core.internal.binder;

interface IPrivilegeServer {
    void shutdown();
    IBinder getUserServiceManager();
    boolean hasSystemService(String serviceName);
    int checkServerPermission(String permission);
    int checkPermission(String permName, String pkgName, int userId);
    void grantRuntimePermission(String packageName, String permissionName, int userId);
}
