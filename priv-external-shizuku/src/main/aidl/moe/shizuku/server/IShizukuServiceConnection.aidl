package moe.shizuku.server;

interface IShizukuServiceConnection {
    oneway void connected(IBinder service) = 0;
    oneway void died() = 1;
}
