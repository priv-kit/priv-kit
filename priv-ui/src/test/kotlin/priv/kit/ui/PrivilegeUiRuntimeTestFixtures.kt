package priv.kit.ui

import priv.kit.PrivilegeServerInfo
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt

internal fun PrivilegeUiRuntimeActions.runServerStart(
    message: String,
    startupSource: String? = null,
    runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
    start: () -> PrivilegeServerInfo,
) {
    runServerStart(
        PrivilegeUiRuntimeStartAttempt.Connect(
            message = message,
            startupSource = startupSource,
            runtimeStartSource = runtimeStartSource,
        ) {
            start()
        },
    )
}

internal fun PrivilegeUiRuntimeActions.runServerStartRequest(
    message: String,
    startedMessage: String,
    startupSource: String? = null,
    runtimeStartSource: PrivilegeUiRuntimeStartSource? = null,
    start: () -> Unit,
) {
    runServerStartRequest(
        PrivilegeUiRuntimeStartAttempt.Request(
            message = message,
            startedMessage = startedMessage,
            startupSource = startupSource,
            runtimeStartSource = runtimeStartSource,
        ) {
            start()
        },
    )
}
