package priv.kit.userservice

import android.os.Bundle
import android.os.IBinder

public object PrivilegeUserServiceContract {
    public const val METHOD_USER_SERVICE_READY: String = "privilege_user_service_ready"
    public const val METHOD_USER_SERVICE_CLAIM: String = "privilege_user_service_claim"

    public const val EXTRA_TOKEN: String = "privilege_user_service_token"
    public const val EXTRA_PROCESS_BINDER: String = "privilege_user_service_process_binder"
    public const val EXTRA_PID: String = "privilege_user_service_pid"

    public const val KEY_SERVICE_CLASS_NAME: String = "privilege_user_service_class_name"
    public const val KEY_TAG: String = "privilege_user_service_tag"
    public const val KEY_VERSION: String = "privilege_user_service_version"
    public const val KEY_PROCESS_MODE: String = "privilege_user_service_process_mode"
    public const val KEY_OWNER_DEATH_POLICY: String = "privilege_user_service_owner_death_policy"
    public const val KEY_DESTROY_TIMEOUT_MILLIS: String = "privilege_user_service_destroy_timeout_millis"
    public const val KEY_SUCCESS: String = "privilege_user_service_success"
    public const val KEY_ERROR_TYPE: String = "privilege_user_service_error_type"
    public const val KEY_ERROR_MESSAGE: String = "privilege_user_service_error_message"
    public const val KEY_CONNECTION_ID: String = "privilege_user_service_connection_id"
    public const val KEY_SERVICE_BINDER: String = "privilege_user_service_binder"
    public const val KEY_STATE: String = "privilege_user_service_state"
    public const val KEY_STARTED: String = "privilege_user_service_started"
    public const val KEY_BOUND_COUNT: String = "privilege_user_service_bound_count"
    public const val KEY_LAST_ERROR: String = "privilege_user_service_last_error"

    public const val ERROR_DECLARATION: String = "declaration"
    public const val ERROR_START: String = "start"
    public const val ERROR_BIND: String = "bind"
    public const val ERROR_NOT_RUNNING: String = "not_running"
    public const val ERROR_UNAVAILABLE: String = "unavailable"

    public fun requestBundle(spec: PrivilegeUserServiceSpec): Bundle =
        Bundle().apply {
            putString(KEY_SERVICE_CLASS_NAME, spec.serviceClassName)
            putString(KEY_TAG, spec.tag)
            putInt(KEY_VERSION, spec.version)
            putInt(KEY_PROCESS_MODE, spec.processMode.wireValue)
            putInt(KEY_OWNER_DEATH_POLICY, spec.ownerDeathPolicy.wireValue)
            putLong(KEY_DESTROY_TIMEOUT_MILLIS, spec.destroyTimeoutMillis)
        }

    public fun specFrom(bundle: Bundle): PrivilegeUserServiceSpec =
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

    public fun successBundle(status: PrivilegeUserServiceStatus): Bundle =
        statusBundle(status).apply {
            putBoolean(KEY_SUCCESS, true)
        }

    public fun bindSuccessBundle(
        connectionId: String,
        binder: IBinder,
        status: PrivilegeUserServiceStatus,
    ): Bundle =
        successBundle(status).apply {
            putString(KEY_CONNECTION_ID, connectionId)
            putBinder(KEY_SERVICE_BINDER, binder)
        }

    public fun errorBundle(
        type: String,
        message: String,
    ): Bundle =
        Bundle().apply {
            putBoolean(KEY_SUCCESS, false)
            putString(KEY_ERROR_TYPE, type)
            putString(KEY_ERROR_MESSAGE, message)
        }

    public fun statusFrom(bundle: Bundle): PrivilegeUserServiceStatus =
        PrivilegeUserServiceStatus(
            id = PrivilegeUserServiceId(
                serviceClassName = requireString(bundle, KEY_SERVICE_CLASS_NAME),
                tag = requireString(bundle, KEY_TAG),
            ),
            version = bundle.getInt(KEY_VERSION, 1),
            processMode = PrivilegeUserServiceProcessMode.fromWireValue(bundle.getInt(KEY_PROCESS_MODE)),
            ownerDeathPolicy = PrivilegeUserServiceOwnerDeathPolicy.fromWireValue(
                bundle.getInt(KEY_OWNER_DEATH_POLICY),
            ),
            state = PrivilegeUserServiceState.fromWireValue(bundle.getInt(KEY_STATE)),
            started = bundle.getBoolean(KEY_STARTED),
            boundCount = bundle.getInt(KEY_BOUND_COUNT),
            pid = bundle.getInt(EXTRA_PID),
            lastError = bundle.getString(KEY_LAST_ERROR),
        )

    internal fun statusBundle(status: PrivilegeUserServiceStatus): Bundle =
        Bundle().apply {
            putString(KEY_SERVICE_CLASS_NAME, status.id.serviceClassName)
            putString(KEY_TAG, status.id.tag)
            putInt(KEY_VERSION, status.version)
            putInt(KEY_PROCESS_MODE, status.processMode.wireValue)
            putInt(KEY_OWNER_DEATH_POLICY, status.ownerDeathPolicy.wireValue)
            putInt(KEY_STATE, status.state.wireValue)
            putBoolean(KEY_STARTED, status.started)
            putInt(KEY_BOUND_COUNT, status.boundCount)
            putInt(EXTRA_PID, status.pid)
            status.lastError?.let { putString(KEY_LAST_ERROR, it) }
        }

    private fun requireString(
        bundle: Bundle,
        key: String,
    ): String =
        bundle.getString(key)?.takeIf { it.isNotBlank() }
            ?: throw PrivilegeUserServiceDeclarationException("UserService request is missing $key")
}
