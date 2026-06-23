package priv.kit.userservice;

import android.os.Bundle;
import android.os.IBinder;

interface IPrivilegeUserServiceManager {
    Bundle startUserService(in Bundle request, IBinder client);
    Bundle bindUserService(in Bundle request, IBinder client);
    Bundle unbindUserService(String connectionId);
    Bundle stopUserService(in Bundle request);
    Bundle getUserServiceStatus(in Bundle request);
}
