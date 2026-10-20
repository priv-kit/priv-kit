package priv.kit.shared

import android.os.IBinder
import android.os.BinderHidden
import android.os.Parcel
import android.os.ResultReceiver
import android.os.ShellCallback
import java.io.FileDescriptor

/** Sends the framework shell-command transaction. The caller owns and reads [reply]. */
public fun IBinder.transactShellCommand(
    input: FileDescriptor?,
    output: FileDescriptor?,
    error: FileDescriptor?,
    args: Array<out String>,
    shellCallback: ShellCallback?,
    resultReceiver: ResultReceiver,
    reply: Parcel,
): Boolean {
    val data = Parcel.obtain()
    try {
        val hiddenData = data.toHidden
        hiddenData.writeFileDescriptor(input)
        hiddenData.writeFileDescriptor(output)
        hiddenData.writeFileDescriptor(error)
        data.writeStringArray(args)
        ShellCallback.writeToParcel(shellCallback, data)
        resultReceiver.writeToParcel(data, 0)
        // Binder inherits this field from IBinder; a class owner avoids R8's interface-field crash.
        return transact(BinderHidden.SHELL_COMMAND_TRANSACTION, data, reply, 0)
    } finally {
        data.recycle()
    }
}
