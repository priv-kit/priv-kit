package priv.kit.core.internal.userservice

import android.os.Bundle
import android.os.IBinder
import android.os.DeadObjectException
import priv.kit.core.binder.PrivilegeUnavailableException
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.core.userservice.PrivilegeUserServiceProcess

internal object PrivilegeUserServiceContract {
    const val METHOD_USER_SERVICE_READY: String = "privilege_user_service_ready"
    const val METHOD_USER_SERVICE_CLAIM: String = "privilege_user_service_claim"

    const val EXTRA_TOKEN: String = "privilege_user_service_token"
    const val EXTRA_PROCESS_BINDER: String = "privilege_user_service_process_binder"

    const val KEY_SERVICE_CLASS_NAME: String = "privilege_user_service_class_name"
    const val KEY_TAG: String = "privilege_user_service_tag"
    const val KEY_PROCESS_UID: String = "privilege_user_service_process_uid"
    const val KEY_SUCCESS: String = "privilege_user_service_success"
    const val KEY_ERROR_MESSAGE: String = "privilege_user_service_error_message"
    const val KEY_CONNECTION_FAILURE: String = "privilege_user_service_connection_failure"
    const val KEY_CONNECTION_ID: String = "privilege_user_service_connection_id"
    const val KEY_SERVICE_BINDER: String = "privilege_user_service_binder"

    fun requestBundle(spec: PrivilegeUserServiceSpec): Bundle =
        Bundle().apply {
            putString(KEY_SERVICE_CLASS_NAME, spec.serviceClassName)
            putString(KEY_TAG, spec.tag)
            putInt(KEY_PROCESS_UID, spec.process.uid)
        }

    fun specFrom(bundle: Bundle): PrivilegeUserServiceSpec =
        try {
            PrivilegeUserServiceSpec(
                serviceClassName = bundle.getString(KEY_SERVICE_CLASS_NAME),
                tag = bundle.getString(KEY_TAG),
                process = PrivilegeUserServiceProcess.fromUid(
                    bundle.getInt(KEY_PROCESS_UID, PrivilegeUserServiceProcess.EMBEDDED.uid),
                ),
            )
        } catch (exception: IllegalArgumentException) {
            throw IllegalStateException(
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

    fun errorBundle(throwable: Throwable): Bundle =
        errorBundle(throwable.message ?: throwable.javaClass.name).apply {
            putInt(KEY_CONNECTION_FAILURE, when (throwable) {
                is PrivilegeUnavailableException -> 1
                is DeadObjectException -> 2
                else -> 0
            })
        }

    fun throwFailure(response: Bundle): Nothing {
        val message = response.getString(KEY_ERROR_MESSAGE) ?: "UserService operation failed"
        when (response.getInt(KEY_CONNECTION_FAILURE)) {
            1 -> throw PrivilegeUnavailableException(message)
            2 -> throw DeadObjectException(message)
            else -> throw IllegalStateException(message)
        }
    }
}
