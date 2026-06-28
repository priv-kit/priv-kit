package priv.kit.userservice;

import android.os.IBinder;

interface IPrivilegeUserServiceProcess {
    void start();
    IBinder bind();
    void unbind(String connectionId);
    void destroy();
}
