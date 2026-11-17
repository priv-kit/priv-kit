package moe.shizuku.server;

import moe.shizuku.server.IShizukuApplication;
import moe.shizuku.server.IShizukuServiceConnection;

interface IShizukuService {
    int addUserService(in IShizukuServiceConnection conn, in Bundle args) = 11;
    int removeUserService(in IShizukuServiceConnection conn, in Bundle args) = 12;
    void requestPermission(int requestCode) = 14;
    boolean checkSelfPermission() = 15;
    void attachApplication(in IShizukuApplication application, in Bundle args) = 17;
}
