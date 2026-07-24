package priv.kit.sample.startup

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import androidx.annotation.Keep
import priv.kit.core.PrivilegeExternalStartupHost
import kotlin.system.exitProcess

internal class PrivilegeSampleShizukuStartService @Keep constructor() :
    IPrivilegeSampleShizukuStartService.Stub() {
    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(
            commandLine = commandLine,
            stdout = stdout,
            stderr = stderr,
            resultReceiver = resultReceiver,
        )
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
