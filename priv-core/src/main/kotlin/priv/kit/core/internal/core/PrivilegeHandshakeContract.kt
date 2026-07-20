package priv.kit.core.internal.core

import android.net.Uri
import java.io.File

internal object PrivilegeHandshakeContract {
    const val METHOD_SERVER_READY: String = "privilege_server_ready"

    const val EXTRA_TOKEN: String = "privilege_token"
    const val EXTRA_SERVER_BINDER: String = "privilege_server_binder"
    const val EXTRA_PROTOCOL_VERSION: String = "privilege_protocol_version"
    const val EXTRA_CLASSPATH_IDENTITY: String = "privilege_classpath_identity"
    const val EXTRA_FOLLOW_DEATH_DELAY_MILLIS: String = "privilege_follow_death_delay_millis"
    const val EXTRA_ACTIVE_RECONNECT_ON_OWNER_DEATH: String = "privilege_active_reconnect_on_owner_death"
    const val EXTRA_INITIAL_LAUNCH_ID: String = "privilege_initial_launch_id"

    const val ENV_INITIAL_LAUNCH_ID: String = "PRIV_KIT_INITIAL_LAUNCH_ID"

    const val RESULT_ACCEPTED: String = "privilege_accepted"
    const val RESULT_TOKEN: String = "privilege_token"
    const val RESULT_OWNER_BINDER: String = "privilege_owner_binder"
    const val RESULT_REPLACEMENT_COMMAND: String = "privilege_replacement_command"

    fun providerAuthority(packageName: String): String = "$packageName.privilege.handshake"

    fun ownerProcessStartedUri(packageName: String): Uri =
        Uri.Builder()
            .scheme("content")
            .authority(providerAuthority(packageName))
            .appendPath("owner-started")
            .build()

    fun classpathIdentity(classpath: String): String =
        classpath.split(':')
            .filter { it.isNotBlank() }
            .joinToString(":") { path ->
                val file = File(path)
                "$path@${file.length()}@${file.lastModified() / 1000L}"
            }
}
