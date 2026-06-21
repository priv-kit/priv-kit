package priv.kit.server

import android.os.Bundle
import android.os.Process
import android.util.Log
import priv.kit.core.PrivilegeHandshakeContract

internal object PrivilegeServerStartTokenResolver {
    fun resolve(config: PrivilegeServerConfig): PrivilegeServerConfig {
        if (config.token.isNotBlank()) {
            return config
        }

        Log.i(TAG, "Requesting token-hidden server start token")
        val response = PrivilegeServerProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeHandshakeContract.METHOD_SERVER_START_TOKEN,
            arg = null,
            extras = config.toStartTokenExtras(),
            userId = config.userId,
        )
        val acceptedResponse = response
            ?.takeIf { it.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false) }
            ?: error("Handshake provider rejected token-hidden server start token request")
        val token = requireNotNull(
            acceptedResponse.getString(PrivilegeHandshakeContract.RESULT_TOKEN)?.takeIf { it.isNotBlank() },
        ) {
            "Accepted start token response is missing ${PrivilegeHandshakeContract.RESULT_TOKEN}"
        }
        return config.copy(token = token)
    }

    private fun PrivilegeServerConfig.toStartTokenExtras(): Bundle =
        Bundle().apply {
            putInt(PrivilegeHandshakeContract.EXTRA_UID, Process.myUid())
            putInt(PrivilegeHandshakeContract.EXTRA_PID, Process.myPid())
            putInt(PrivilegeHandshakeContract.EXTRA_LAUNCH_MODE, launchMode)
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, protocolVersion)
            putString(PrivilegeHandshakeContract.EXTRA_SERVER_VERSION, serverVersion)
            putString(PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY, classpathIdentity)
        }

    private const val TAG = "PrivKitServer"
}
