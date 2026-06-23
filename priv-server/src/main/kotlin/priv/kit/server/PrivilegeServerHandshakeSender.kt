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
            putInt(PrivilegeHandshakeContract.EXTRA_LAUNCH_MODE, config.launchMode)
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, config.protocolVersion)
            putString(PrivilegeHandshakeContract.EXTRA_SERVER_VERSION, config.serverVersion)
            putString(PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY, config.classpathIdentity)
        }
        Log.i(TAG, "Calling handshake provider authority=${config.providerAuthority}")
        val response = PrivilegeServerProviderCall.call(
            authority = config.providerAuthority,
            method = PrivilegeHandshakeContract.METHOD_SERVER_READY,
            arg = config.token,
            extras = extras,
            userId = config.userId,
        )
        val accepted = response?.getBoolean(PrivilegeHandshakeContract.RESULT_ACCEPTED, false) == true
        val shouldShutdown = response?.getBoolean(
            PrivilegeHandshakeContract.RESULT_SHOULD_SHUTDOWN,
            false,
        ) == true
        val ownerBinder = response?.getBinder(PrivilegeHandshakeContract.RESULT_OWNER_BINDER)
        val restartCommandLine = response?.getString(PrivilegeHandshakeContract.RESULT_RESTART_COMMAND_LINE)
        val ownerConfig = if (accepted) {
            requireNotNull(ownerBinder) {
                "Accepted handshake response is missing ${PrivilegeHandshakeContract.RESULT_OWNER_BINDER}"
            }
            response.toOwnerConfig(config)
        } else {
            config
        }
        Log.i(
            TAG,
            "Handshake provider response accepted=$accepted, hasResponse=${response != null}, " +
                "shouldShutdown=$shouldShutdown, hasRestartCommand=${!restartCommandLine.isNullOrBlank()}, " +
                "followDeathDelayMillis=${ownerConfig.followDeathDelayMillis}, " +
                "activeReconnectOnOwnerDeath=${ownerConfig.activeReconnectOnOwnerDeath}",
        )
        return Result(
            accepted = accepted,
            shouldShutdown = shouldShutdown,
            restartCommandLine = restartCommandLine,
            ownerBinder = ownerBinder,
            ownerConfig = ownerConfig,
        )
    }

    data class Result(
        val accepted: Boolean,
        val shouldShutdown: Boolean,
        val restartCommandLine: String?,
        val ownerBinder: IBinder?,
        val ownerConfig: PrivilegeServerConfig,
    )

    private fun Bundle.toOwnerConfig(base: PrivilegeServerConfig): PrivilegeServerConfig {
        require(containsKey(PrivilegeHandshakeContract.RESULT_FOLLOW_DEATH_DELAY_MILLIS)) {
            "Accepted handshake response is missing ${PrivilegeHandshakeContract.RESULT_FOLLOW_DEATH_DELAY_MILLIS}"
        }
        require(containsKey(PrivilegeHandshakeContract.RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH)) {
            "Accepted handshake response is missing ${PrivilegeHandshakeContract.RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH}"
        }
        return base.copy(
            followDeathDelayMillis = getLong(PrivilegeHandshakeContract.RESULT_FOLLOW_DEATH_DELAY_MILLIS),
            activeReconnectOnOwnerDeath = getBoolean(
                PrivilegeHandshakeContract.RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
            ),
        )
    }

    private const val TAG = "PrivKitServer"
}
