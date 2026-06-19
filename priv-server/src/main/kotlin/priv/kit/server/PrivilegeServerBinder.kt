package priv.kit.server

import android.os.Process
import android.util.Log
import priv.kit.core.IPrivilegeServer
import kotlin.system.exitProcess

internal class PrivilegeServerBinder(
    private val config: PrivilegeServerConfig,
) : IPrivilegeServer.Stub() {
    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    override fun getMode(): Int = config.mode

    override fun getProtocolVersion(): Int = config.protocolVersion

    override fun getServerVersion(): String = config.serverVersion

    override fun shutdown() {
        Log.i(TAG, "Shutdown requested by client")
        Thread {
            Thread.sleep(SHUTDOWN_DELAY_MILLIS)
            exitProcess(0)
        }.start()
    }

    companion object {
        private const val TAG = "PrivKitServer"
        private const val SHUTDOWN_DELAY_MILLIS = 50L
    }
}
