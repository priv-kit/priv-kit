package priv.kit.core.internal.userservice;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;

interface IPrivilegeUserServiceManager {
    oneway void startUserService(
        String operationId,
        in Bundle request,
        IBinder client,
        in ResultReceiver receiver
    );
    oneway void bindUserService(
        String operationId,
        in Bundle request,
        IBinder client,
        in ResultReceiver receiver
    );
    oneway void cancelUserServiceOperation(String operationId);
    oneway void acknowledgeUserServiceOperation(String operationId);
    oneway void unbindUserService(
        String operationId,
        String connectionId,
        IBinder client,
        in ResultReceiver receiver
    );
    oneway void stopUserService(
        String operationId,
        in Bundle request,
        IBinder client,
        in ResultReceiver receiver
    );
}
