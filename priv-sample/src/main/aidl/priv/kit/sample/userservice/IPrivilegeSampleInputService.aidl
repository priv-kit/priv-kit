package priv.kit.sample.userservice;

import android.os.IBinder;

interface IPrivilegeSampleInputService {
    IBinder getDeviceController() = 0;
    void destroy() = 16777114;
}
