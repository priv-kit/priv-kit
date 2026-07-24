package priv.kit.ui

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.PrivilegeServerInfo
import priv.kit.ui.runtime.PrivilegeUiRuntimeActions
import priv.kit.ui.runtime.PrivilegeUiRuntimeStartAttempt
import priv.kit.ui.state.PrivilegeUiViewModelStore

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

internal suspend fun waitUntilIdle(store: PrivilegeUiViewModelStore): Boolean =
    withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
        store.state.first {
            !it.busy && it.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE
        }
        true
    } ?: false

internal suspend fun waitUntil(condition: () -> Boolean): Boolean =
    withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
        while (!condition()) delay(10L)
        true
    } ?: false
