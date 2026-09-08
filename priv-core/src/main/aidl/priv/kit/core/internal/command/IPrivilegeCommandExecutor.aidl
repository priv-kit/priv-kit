package priv.kit.core.internal.command;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

interface IPrivilegeCommandExecutor {
    oneway void startCommand(
        String operationId,
        in Bundle request,
        IBinder client,
        in ParcelFileDescriptor stdoutSink,
        in ParcelFileDescriptor stderrSink,
        in ResultReceiver receiver
    );
    oneway void cancelCommand(String operationId);
}
