package priv.kit.userservice

import android.os.Bundle
import android.os.IBinder

object PrivilegeUserServiceContract {
    const val METHOD_USER_SERVICE_READY = "privilege_user_service_ready"
    const val METHOD_USER_SERVICE_CLAIM = "privilege_user_service_claim"

    const val EXTRA_TOKEN = "privilege_user_service_token"
    const val EXTRA_PROCESS_BINDER = "privilege_user_service_process_binder"
    const val EXTRA_PID = "privilege_user_service_pid"

    const val KEY_SERVICE_CLASS_NAME = "privilege_user_service_class_name"
    const val KEY_TAG = "privilege_user_service_tag"
    const val KEY_VERSION = "privilege_user_service_version"
    const val KEY_PROCESS_MODE = "privilege_user_service_process_mode"
    const val KEY_OWNER_DEATH_POLICY = "privilege_user_service_owner_death_policy"
    const val KEY_DESTROY_TIMEOUT_MILLIS = "privilege_user_service_destroy_timeout_millis"
    const val KEY_SUCCESS = "privilege_user_service_success"
    const val KEY_ERROR_TYPE = "privilege_user_service_error_type"
    const val KEY_ERROR_MESSAGE = "privilege_user_service_error_message"
    const val KEY_CONNECTION_ID = "privilege_user_service_connection_id"
    const val KEY_SERVICE_BINDER = "privilege_user_service_binder"
    const val KEY_STATE = "privilege_user_service_state"
    const val KEY_STARTED = "privilege_user_service_started"
    const val KEY_BOUND_COUNT = "privilege_user_service_bound_count"
    const val KEY_LAST_ERROR = "privilege_user_service_last_error"

    const val ERROR_DECLARATION = "declaration"
    const val ERROR_START = "start"
    const val ERROR_BIND = "bind"
    const val ERROR_NOT_RUNNING = "not_running"
    const val ERROR_UNAVAILABLE = "unavailable"

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

    fun successBundle(status: PrivilegeUserServiceStatus): Bundle =
        statusBundle(status).apply {
            putBoolean(KEY_SUCCESS, true)
        }

    fun bindSuccessBundle(
        connectionId: String,
        binder: IBinder,
        status: PrivilegeUserServiceStatus,
    ): Bundle =
        successBundle(status).apply {
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

    fun statusFrom(bundle: Bundle): PrivilegeUserServiceStatus =
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
        requireNotNull(bundle.getString(key)?.takeIf { it.isNotBlank() }) {
            "UserService request is missing $key"
        }
}
