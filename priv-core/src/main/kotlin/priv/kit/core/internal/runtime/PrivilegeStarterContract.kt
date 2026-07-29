package priv.kit.core.internal.runtime

import priv.kit.core.PrivilegeExistingServerStopException

internal object PrivilegeStarterContract {
    const val STOP_EXISTING_SERVER_FAILED_EXIT_CODE: Int = 9
    const val STOP_EXISTING_SERVER_FAILED_MARKER: String =
        "PRIV_KIT_STARTER_STOP_EXISTING_SERVER_FAILED"

    fun requireNoStopExistingServerFailure(output: String) {
        if (STOP_EXISTING_SERVER_FAILED_MARKER in output) {
            throw PrivilegeExistingServerStopException(
                "Native starter could not stop the existing Privileged Server: $output",
            )
        }
    }
}
