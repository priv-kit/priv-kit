package priv.kit.core.internal.command

import android.os.Bundle
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.command.PrivilegeCommandException

internal object PrivilegeCommandContract {
    const val RESULT_STARTED: Int = 1
    const val RESULT_EXITED: Int = 2
    const val RESULT_FAILED: Int = 3
    const val RESULT_TIMED_OUT: Int = 4

    const val KEY_ARGUMENTS: String = "privilege_command_arguments"
    const val KEY_ENVIRONMENT: String = "privilege_command_environment"
    const val KEY_WORKING_DIRECTORY: String = "privilege_command_working_directory"
    const val KEY_TIMEOUT_MILLIS: String = "privilege_command_timeout_millis"
    const val KEY_EXIT_CODE: String = "privilege_command_exit_code"
    const val KEY_ERROR_MESSAGE: String = "privilege_command_error_message"

    fun requestBundle(
        command: PrivilegeCommand,
        timeoutMillis: Long?,
    ): Bundle = Bundle().apply {
        putStringArray(KEY_ARGUMENTS, command.arguments.toTypedArray())
        putBundle(
            KEY_ENVIRONMENT,
            Bundle().apply {
                command.environment.forEach(::putString)
            },
        )
        putString(KEY_WORKING_DIRECTORY, command.workingDirectory)
        putLong(KEY_TIMEOUT_MILLIS, timeoutMillis ?: 0L)
    }

    fun requestFrom(bundle: Bundle): PrivilegeCommandRequest {
        val arguments = bundle.getStringArray(KEY_ARGUMENTS)?.toList().orEmpty()
        val environmentBundle = bundle.getBundle(KEY_ENVIRONMENT) ?: Bundle.EMPTY
        val environment = environmentBundle.keySet().associateWith { key ->
            environmentBundle.getString(key)
                ?: throw PrivilegeCommandException("Environment value is missing for $key")
        }
        val timeoutMillis = bundle.getLong(KEY_TIMEOUT_MILLIS, 0L)
        if (timeoutMillis < 0L) {
            throw PrivilegeCommandException("Command timeout must not be negative")
        }
        val command = try {
            PrivilegeCommand(
                arguments = arguments,
                environment = environment,
                workingDirectory = bundle.getString(KEY_WORKING_DIRECTORY),
            )
        } catch (exception: IllegalArgumentException) {
            throw PrivilegeCommandException(
                exception.message ?: "Invalid command request",
                exception,
            )
        }
        return PrivilegeCommandRequest(
            command = command,
            timeoutMillis = timeoutMillis,
        )
    }

    fun exitBundle(exitCode: Int): Bundle = Bundle().apply {
        putInt(KEY_EXIT_CODE, exitCode)
    }

    fun errorBundle(message: String): Bundle = Bundle().apply {
        putString(KEY_ERROR_MESSAGE, message)
    }
}

internal data class PrivilegeCommandRequest(
    val command: PrivilegeCommand,
    val timeoutMillis: Long,
)
