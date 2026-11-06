package priv.kit.core.internal.userservice;

import android.os.IBinder;

interface IPrivilegeUserServiceProcess {
    void start();
    IBinder bind();
    void destroy();
    IBinder getForwardingBinder();
    int getUid();
    void attachServer(IBinder lifecycle);
}
