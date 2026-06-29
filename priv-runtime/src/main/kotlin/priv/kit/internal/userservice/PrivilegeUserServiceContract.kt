package priv.kit.internal.userservice

import android.os.Bundle
import android.os.IBinder
import priv.kit.userservice.PrivilegeUserServiceDeclarationException
import priv.kit.userservice.PrivilegeUserServiceOwnerDeathPolicy
import priv.kit.userservice.PrivilegeUserServiceProcessMode
import priv.kit.userservice.PrivilegeUserServiceSpec

internal object PrivilegeUserServiceContract {
    const val METHOD_USER_SERVICE_READY: String = "privilege_user_service_ready"
    const val METHOD_USER_SERVICE_CLAIM: String = "privilege_user_service_claim"

    const val EXTRA_TOKEN: String = "privilege_user_service_token"
    const val EXTRA_PROCESS_BINDER: String = "privilege_user_service_process_binder"

    const val KEY_SERVICE_CLASS_NAME: String = "privilege_user_service_class_name"
    const val KEY_TAG: String = "privilege_user_service_tag"
    const val KEY_VERSION: String = "privilege_user_service_version"
    const val KEY_PROCESS_MODE: String = "privilege_user_service_process_mode"
    const val KEY_OWNER_DEATH_POLICY: String = "privilege_user_service_owner_death_policy"
    const val KEY_DESTROY_TIMEOUT_MILLIS: String = "privilege_user_service_destroy_timeout_millis"
    const val KEY_SUCCESS: String = "privilege_user_service_success"
    const val KEY_ERROR_TYPE: String = "privilege_user_service_error_type"
    const val KEY_ERROR_MESSAGE: String = "privilege_user_service_error_message"
    const val KEY_CONNECTION_ID: String = "privilege_user_service_connection_id"
    const val KEY_SERVICE_BINDER: String = "privilege_user_service_binder"

    const val ERROR_DECLARATION: String = "declaration"
    const val ERROR_START: String = "start"
    const val ERROR_BIND: String = "bind"
    const val ERROR_NOT_RUNNING: String = "not_running"
    const val ERROR_UNAVAILABLE: String = "unavailable"

    fun requestBundle(spec: PrivilegeUserServiceSpec): Bundle =
        Bundle().apply {
            putString(KEY_SERVICE_CLASS_NAME, spec.serviceClassName)
            putString(KEY_TAG, spec.tag)
            putInt(KEY_VERSION, spec.version)
            putInt(KEY_PROCESS_MODE, spec.processMode.wireValue)
            putInt(KEY_OWNER_DEATH_POLICY, spec.ownerDeathPolicy.wireValue)
            putLong(KEY_DESTROY_TIMEOUT_MILLIS, spec.destroyTimeoutMillis)
        }

    fun specFrom(bundle: Bundle): PrivilegeUserServiceSpec =
        try {
            PrivilegeUserServiceSpec(
                serviceClassName = requireString(bundle, KEY_SERVICE_CLASS_NAME),
                tag = requireString(bundle, KEY_TAG),
                version = bundle.getInt(KEY_VERSION, 1),
                processMode = PrivilegeUserServiceProcessMode.fromWireValue(
                    bundle.getInt(KEY_PROCESS_MODE, PrivilegeUserServiceProcessMode.DEDICATED_PROCESS.wireValue),
                ),
                ownerDeathPolicy = PrivilegeUserServiceOwnerDeathPolicy.fromWireValue(
                    bundle.getInt(
                        KEY_OWNER_DEATH_POLICY,
                        PrivilegeUserServiceOwnerDeathPolicy.DESTROY_ON_OWNER_DEATH.wireValue,
                    ),
                ),
                destroyTimeoutMillis = bundle.getLong(
                    KEY_DESTROY_TIMEOUT_MILLIS,
                    PrivilegeUserServiceSpec.DEFAULT_DESTROY_TIMEOUT_MILLIS,
                ),
            )
        } catch (exception: IllegalArgumentException) {
            throw PrivilegeUserServiceDeclarationException(
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

    fun errorBundle(
        type: String,
        message: String,
    ): Bundle =
        Bundle().apply {
            putBoolean(KEY_SUCCESS, false)
            putString(KEY_ERROR_TYPE, type)
            putString(KEY_ERROR_MESSAGE, message)
        }

    private fun requireString(
        bundle: Bundle,
        key: String,
    ): String =
        bundle.getString(key)?.takeIf { it.isNotBlank() }
            ?: throw PrivilegeUserServiceDeclarationException("UserService request is missing $key")
}
