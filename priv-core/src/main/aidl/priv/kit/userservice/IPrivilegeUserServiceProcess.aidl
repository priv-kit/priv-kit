package priv.kit.userservice;

import android.os.IBinder;

interface IPrivilegeUserServiceProcess {
    int getPid();
    void start();
    IBinder bind();
    void unbind(String connectionId);
    void destroy();
}
