package priv.kit.internal.server

import android.os.Bundle
import android.os.IBinder
import android.util.Log
import priv.kit.internal.core.PrivilegeContentProviderCall
import priv.kit.internal.core.PrivilegeHandshakeContract
import java.io.File

internal object PrivilegeServerHandshakeSender {
    fun send(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
    ): Result =
        send(
            config = config,
            serverBinder = serverBinder,
            providerCall = PrivilegeServerHandshakeSender::callHandshakeProvider,
            replacementStarter = PrivilegeServerReplacementStarter::start,
        )

    internal fun send(
        config: PrivilegeServerConfig,
        serverBinder: PrivilegeServerBinder,
        providerCall: (
            authority: String,
            method: String,
            arg: String?,
            extras: Bundle,
            userId: Int,
        ) -> Bundle?,
        replacementStarter: (String) -> Unit,
    ): Result {
        val extras = Bundle().apply {
            config.token.takeIf { it.isNotBlank() }?.let {
                putString(PrivilegeHandshakeContract.EXTRA_TOKEN, it)
            }
            putBinder(PrivilegeHandshakeContract.EXTRA_SERVER_BINDER, serverBinder.asBinder())
            putInt(PrivilegeHandshakeContract.EXTRA_PROTOCOL_VERSION, config.protocolVersion)
            putString(
                PrivilegeHandshakeContract.EXTRA_CLASSPATH_IDENTITY,
                PrivilegeHandshakeContract.classpathIdentity(config.classpath),
            )
        }
        val providerAuthority = PrivilegeHandshakeContract.providerAuthority(config.packageName)
        Log.i(TAG, "Calling handshake provider authority=$providerAuthority")
        val response = providerCall(
            providerAuthority,
            PrivilegeHandshakeContract.METHOD_SERVER_READY,
            config.token.takeIf { it.isNotBlank() },
            extras,
            config.userId,
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
        val replacementStarted = if (!accepted) {
            response?.getString(PrivilegeHandshakeContract.RESULT_REPLACEMENT_COMMAND)
                ?.takeIf { it.isNotBlank() }
                ?.let { commandLine ->
                    Log.i(TAG, "Starting replacement Privileged Server from current install")
                    replacementStarter(commandLine)
                    true
                } == true
        } else {
            false
        }
        Log.i(
            TAG,
            "Handshake provider response accepted=$accepted, hasResponse=${response != null}, " +
                "replacementStarted=$replacementStarted, " +
                "followDeathDelayMillis=${ownerConfig.followDeathDelayMillis}, " +
                "activeReconnectOnOwnerDeath=${ownerConfig.activeReconnectOnOwnerDeath}",
        )
        return Result(
            accepted = accepted,
            ownerBinder = ownerBinder,
            ownerConfig = ownerConfig,
            replacementStarted = replacementStarted,
        )
    }

    data class Result(
        val accepted: Boolean,
        val ownerBinder: IBinder?,
        val ownerConfig: PrivilegeServerConfig,
        val replacementStarted: Boolean = false,
    )

    private fun callHandshakeProvider(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle,
        userId: Int,
    ): Bundle? =
        PrivilegeContentProviderCall.call(
            authority = authority,
            method = method,
            arg = arg,
            extras = extras,
            userId = userId,
            logTag = TAG,
        )

    private fun Bundle.requireLong(key: String): Long {
        require(containsKey(key)) { "Accepted initial handshake response is missing $key" }
        return getLong(key)
    }

    private fun Bundle.requireBoolean(key: String): Boolean {
        require(containsKey(key)) { "Accepted initial handshake response is missing $key" }
        return getBoolean(key)
    }

    private const val TAG = "PrivKitServer"
}

internal object PrivilegeServerReplacementStarter {
    fun start(commandLine: String) {
        ProcessBuilder("/system/bin/sh", "-c", commandLine)
            .redirectInput(NULL_DEVICE)
            .redirectOutput(NULL_DEVICE)
            .redirectError(NULL_DEVICE)
            .start()
    }

    private val NULL_DEVICE = File("/dev/null")
}
