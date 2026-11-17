package priv.kit.external.shizuku.internal

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import kotlin.system.exitProcess
import priv.kit.core.PrivilegeExternalStartupHost

internal class ShizukuStartService : IShizukuStartService.Stub() {
    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(commandLine, stdout, stderr, resultReceiver)
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
