package priv.kit.core.internal.core

import android.net.Uri
import android.os.Bundle
import java.io.File

internal object PrivilegeHandshakeContract {
    const val METHOD_SERVER_READY: String = "privilege_server_ready"

    const val EXTRA_SERVER_BINDER: String = "privilege_server_binder"
    const val EXTRA_SERVER_LIFECYCLE_BINDER: String = "privilege_server_lifecycle_binder"
    const val EXTRA_SERVER_SERVICE_ENDPOINTS: String = "privilege_server_service_endpoints"
    const val EXTRA_PROTOCOL_VERSION: String = "privilege_protocol_version"
    const val EXTRA_CLASSPATH_IDENTITY: String = "privilege_classpath_identity"
    const val EXTRA_FOLLOW_DEATH_DELAY_MILLIS: String = "privilege_follow_death_delay_millis"
    const val EXTRA_ACTIVE_RECONNECT_ON_OWNER_DEATH: String = "privilege_active_reconnect_on_owner_death"
    const val EXTRA_LAUNCH_CORRELATION_ID: String = "privilege_launch_correlation_id"
    const val EXTRA_OWNER_RECONNECT: String = "privilege_owner_reconnect"

    const val ENV_LAUNCH_CORRELATION_ID: String = "PRIV_KIT_LAUNCH_CORRELATION_ID"
    const val ENV_OWNER_USER_ID: String = "PRIV_KIT_OWNER_USER_ID"

    const val RESULT_ACCEPTED: String = "privilege_accepted"
    const val RESULT_OWNER_BINDER: String = "privilege_owner_binder"
    const val RESULT_REPLACEMENT_COMMAND: String = "privilege_replacement_command"

    private const val SERVICE_ENDPOINT_FILE_SYSTEM_BINDER: String =
        "privilege_file_system_binder"
    private const val SERVICE_ENDPOINT_USER_SERVICE_MANAGER_BINDER: String =
        "privilege_user_service_manager_binder"

    fun putServiceEndpoints(
        extras: Bundle,
        endpoints: PrivilegeServerServiceEndpoints,
    ) {
        val endpointExtras = Bundle().apply {
            putBinder(SERVICE_ENDPOINT_FILE_SYSTEM_BINDER, endpoints.fileSystemBinder)
            putBinder(
                SERVICE_ENDPOINT_USER_SERVICE_MANAGER_BINDER,
                endpoints.userServiceManagerBinder,
            )
        }
        extras.putBundle(EXTRA_SERVER_SERVICE_ENDPOINTS, endpointExtras)
    }

    fun serviceEndpointsOrNull(extras: Bundle): PrivilegeServerServiceEndpoints? {
        val endpointExtras = extras.getBundle(EXTRA_SERVER_SERVICE_ENDPOINTS) ?: return null
        val fileSystemBinder =
            endpointExtras.getBinder(SERVICE_ENDPOINT_FILE_SYSTEM_BINDER) ?: return null
        val userServiceManagerBinder =
            endpointExtras.getBinder(SERVICE_ENDPOINT_USER_SERVICE_MANAGER_BINDER) ?: return null
        return PrivilegeServerServiceEndpoints(
            fileSystemBinder = fileSystemBinder,
            userServiceManagerBinder = userServiceManagerBinder,
        )
    }

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
