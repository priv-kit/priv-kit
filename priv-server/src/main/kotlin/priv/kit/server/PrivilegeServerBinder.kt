package priv.kit.server

import android.os.Process
import priv.kit.core.IPrivilegeServer

internal class PrivilegeServerBinder(
    private val config: PrivilegeServerConfig,
) : IPrivilegeServer.Stub() {
    override fun getUid(): Int = Process.myUid()

    override fun getPid(): Int = Process.myPid()

    override fun getMode(): Int = config.mode

    override fun getProtocolVersion(): Int = config.protocolVersion

    override fun getServerVersion(): String = config.serverVersion
}
