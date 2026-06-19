package priv.kit.server

import android.os.Bundle
import android.os.Process
import priv.kit.core.PrivilegeHandshakeContract

internal object PrivilegeServerHandshakeSender {
    fun send(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
    ): Boolean {
        val extras = Bundle().apply {
            putString(PrivilegeHandshakeContract.EXTRA_TOKEN, config.token)
            putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, serverBinder.asBinder())
            putInt(PrivilegeHandshakeContract.EXTRA_UID, Process.myUid())
            putInt(PrivilegeHandshakeContract.EXTRA_PID, Process.myPid())
            putInt(PrivilegeHandshakeContract.EXTRA_MODE, config.mode)
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, config.protocolVersion)
            putString(PrivilegeHandshakeContract.EXTRA_SERVER_VERSION, config.serverVersion)
        }
        val response = PrivilegeServerProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeHandshakeContract.METHOD_SERVER_READY,
            arg = config.token,
            extras = extras,
        )
        return response?.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false) == true
    }
}
