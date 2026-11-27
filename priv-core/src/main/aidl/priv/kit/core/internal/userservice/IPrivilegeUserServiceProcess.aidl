package priv.kit.core.internal.userservice;

import android.os.IBinder;

interface IPrivilegeUserServiceProcess {
    IBinder bind(String connectionId);
    void unbind(String connectionId);
    void closeBindings();
    void destroy();
    void attachServer(IBinder lifecycle);
}
