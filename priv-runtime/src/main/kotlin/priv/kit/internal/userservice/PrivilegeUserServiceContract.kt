package priv.kit.internal.userservice

import android.os.Bundle
import android.os.IBinder
import priv.kit.userservice.PrivilegeUserServiceException
import priv.kit.userservice.PrivilegeUserServiceSpec

internal object PrivilegeUserServiceContract {
    const val METHOD_USER_SERVICE_READY: String = "privilege_user_service_ready"
    const val METHOD_USER_SERVICE_CLAIM: String = "privilege_user_service_claim"

    const val EXTRA_TOKEN: String = "privilege_user_service_token"
    const val EXTRA_PROCESS_BINDER: String = "privilege_user_service_process_binder"

    const val KEY_SERVICE_CLASS_NAME: String = "privilege_user_service_class_name"
    const val KEY_TAG: String = "privilege_user_service_tag"
    const val KEY_VERSION: String = "privilege_user_service_version"
    const val KEY_EMBEDDED: String = "privilege_user_service_embedded"
    const val KEY_DAEMON: String = "privilege_user_service_daemon"
    const val KEY_SUCCESS: String = "privilege_user_service_success"
    const val KEY_ERROR_MESSAGE: String = "privilege_user_service_error_message"
    const val KEY_CONNECTION_ID: String = "privilege_user_service_connection_id"
    const val KEY_SERVICE_BINDER: String = "privilege_user_service_binder"

    fun requestBundle(spec: PrivilegeUserServiceSpec): Bundle =
        Bundle().apply {
            putString(KEY_SERVICE_CLASS_NAME, spec.serviceClassName)
            putString(KEY_TAG, spec.tag)
            putInt(KEY_VERSION, spec.version)
            putBoolean(KEY_EMBEDDED, spec.embedded)
            putBoolean(KEY_DAEMON, spec.daemon)
        }

    fun specFrom(bundle: Bundle): PrivilegeUserServiceSpec =
        try {
            PrivilegeUserServiceSpec(
                serviceClassName = requireString(bundle, KEY_SERVICE_CLASS_NAME),
                tag = requireString(bundle, KEY_TAG),
                version = bundle.getInt(KEY_VERSION, 1),
                embedded = bundle.getBoolean(KEY_EMBEDDED, false),
                daemon = bundle.getBoolean(KEY_DAEMON, false),
            )
        } catch (exception: IllegalArgumentException) {
            throw PrivilegeUserServiceException(
                exception.message ?: "Invalid UserService request",
                exception,
            )
        }

    fun successBundle(): Bundle =
        Bundle().apply {
            putBoolean(KEY_SUCCESS, true)
        }

    fun bindSuccessBundle(
        connectionId: String,
        binder: IBinder,
    ): Bundle =
        successBundle().apply {
            putString(KEY_CONNECTION_ID, connectionId)
            putBinder(KEY_SERVICE_BINDER, binder)
        }

    fun errorBundle(message: String): Bundle =
        Bundle().apply {
            putBoolean(KEY_SUCCESS, false)
            putString(KEY_ERROR_MESSAGE, message)
        }

    private fun requireString(
        bundle: Bundle,
        key: String,
    ): String =
        bundle.getString(key)?.takeIf { it.isNotBlank() }
            ?: throw PrivilegeUserServiceException("UserService request is missing $key")
}
