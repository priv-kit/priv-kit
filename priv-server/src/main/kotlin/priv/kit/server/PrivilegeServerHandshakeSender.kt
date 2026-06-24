package priv.kit.server

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import priv.kit.core.PrivilegeHandshakeContract
import java.io.File

internal object PrivilegeServerHandshakeSender {
    fun send(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
    ): Result {
        val extras = Bundle().apply {
            config.token.takeIf { it.isNotBlank() }?.let {
                putString(PrivilegeHandshakeContract.EXTRA_TOKEN, it)
            }
            putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, serverBinder.asBinder())
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, config.protocolVersion)
            putString(PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY, buildClasspathIdentity(config.classpath))
        }
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(config.packageName)
        Log.i(TAG, "Calling handshake provider authority=$providerAuthority")
        val response = PrivilegeServerProviderCall.call(
            authority = providerAuthority,
            method = PrivilegeHandshakeContract.METHOD_SERVER_READY,
            arg = config.token.takeIf { it.isNotBlank() },
            extras = extras,
            userId = config.userId,
        )
        val accepted = response?.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false) == true
        val ownerBinder = response?.getBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER)
        if (accepted) {
            requireNotNull(ownerBinder) {
                "Accepted handshake response is missing ${PrivilegeHandshakeContract.RESULT_OWNER_BINDER}"
            }
        }
        val ownerConfig = if (accepted && config.token.isBlank()) {
            val token = requireNotNull(
                response.getString(PrivilegeHandshakeContract.RESULT_TOKEN)?.takeIf { it.isNotBlank() },
            ) {
                "Accepted initial handshake response is missing ${PrivilegeHandshakeContract.RESULT_TOKEN}"
            }
            config.copy(
                token = token,
                followDeathDelayMillis = response.requireLong(
                    PrivilegeHandshakeContract.EXTRA_FOLLOW_DEATH_DELAY_MILLIS,
                ),
                activeReconnectOnOwnerDeath = response.requireBoolean(
                    PrivilegeHandshakeContract.EXTRA_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                ),
            )
        } else {
            config
        }
        Log.i(
            TAG,
            "Handshake provider response accepted=$accepted, hasResponse=${response != null}, " +
                "followDeathDelayMillis=${ownerConfig.followDeathDelayMillis}, " +
                "activeReconnectOnOwnerDeath=${ownerConfig.activeReconnectOnOwnerDeath}",
        )
        return Result(
            accepted = accepted,
            ownerBinder = ownerBinder,
            ownerConfig = ownerConfig,
        )
    }

    data class Result(
        val accepted: Boolean,
        val ownerBinder: IBinder?,
        val ownerConfig: PrivilegeServerConfig,
    )

    private fun Bundle.requireLong(key: String): Long {
        require(containsKey(key)) { "Accepted initial handshake response is missing $key" }
        return getLong(key)
    }

    private fun Bundle.requireBoolean(key: String): Boolean {
        require(containsKey(key)) { "Accepted initial handshake response is missing $key" }
        return getBoolean(key)
    }

    private fun buildClasspathIdentity(classpath: String): String =
        classpath.split(':')
            .filter { it.isNotBlank() }
            .joinToString(":") { path ->
                val file = File(path)
                "$path@${file.length()}@${file.lastModified() / 1000L}"
            }

    private const val TAG = "PrivKitServer"
}
