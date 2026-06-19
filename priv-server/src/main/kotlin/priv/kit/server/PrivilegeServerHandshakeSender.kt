package priv.kit.server

import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import priv.kit.core.PrivilegeHandshakeContract

internal object PrivilegeServerHandshakeSender {
    fun send(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
    ): Result {
        val extras = Bundle().apply {
            putString(PrivilegeHandshakeContract.EXTRA_TOKEN, config.token)
            putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, serverBinder.asBinder())
            putInt(PrivilegeHandshakeContract.EXTRA_UID, Process.myUid())
            putInt(PrivilegeHandshakeContract.EXTRA_PID, Process.myPid())
            putInt(PrivilegeHandshakeContract.EXTRA_MODE, config.mode)
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, config.protocolVersion)
            putString(PrivilegeHandshakeContract.EXTRA_SERVER_VERSION, config.serverVersion)
        }
        Log.i(TAG, "Calling handshake provider authority=${config.providerAuthority}")
        val response = PrivilegeServerProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeHandshakeContract.METHOD_SERVER_READY,
            arg = config.token,
            extras = extras,
        )
        val accepted = response?.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false) == true
        val ownerBinder = response?.getBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER)
        Log.i(TAG, "Handshake provider response accepted=$accepted, hasResponse=${response != null}")
        return Result(
            accepted = accepted,
            ownerBinder = ownerBinder,
        )
    }

    data class Result(
        val accepted: Boolean,
        val ownerBinder: IBinder?,
    )

    private const val TAG = "PrivKitServer"
}
