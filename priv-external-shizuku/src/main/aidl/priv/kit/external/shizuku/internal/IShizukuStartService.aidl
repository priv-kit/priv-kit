package priv.kit.external.shizuku.internal;

import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

interface IShizukuStartService {
    void start(
        String commandLine,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        in ResultReceiver resultReceiver
    ) = 1;
    oneway void destroy() = 16777114;
}
