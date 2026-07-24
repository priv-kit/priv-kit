package priv.kit.ui

import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt

internal fun PrivilegeUiRuntimeActions.runServerStart(
    message: String,
    startupSource: String?,
    runtimeStartSource: PrivilegeUiRuntimeStartSource?,
    start: () -> PrivilegeServerInfo,
) {
    runServerStart(
        PrivilegeUiRuntimeStartAttempt.Connect(
            progressText = PrivilegeUiText.Literal(message),
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
    startupSource: String?,
    runtimeStartSource: PrivilegeUiRuntimeStartSource?,
    start: () -> Unit,
) {
    runServerStartRequest(
        PrivilegeUiRuntimeStartAttempt.Request(
            progressText = PrivilegeUiText.Literal(message),
            startedText = PrivilegeUiText.Literal(startedMessage),
            startupSource = startupSource,
            runtimeStartSource = runtimeStartSource,
        ) {
            start()
        },
    )
}
