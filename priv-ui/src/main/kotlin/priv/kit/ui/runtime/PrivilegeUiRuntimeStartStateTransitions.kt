package priv.kit.ui.runtime

import priv.kit.ui.PrivilegeUiAdbRestrictionStatus
import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStatus
import priv.kit.ui.PrivilegeUiState

internal fun PrivilegeUiState.startingAttempt(
    attempt: PrivilegeUiRuntimeStartAttempt,
    phase: PrivilegeUiRuntimeStartPhase,
): PrivilegeUiState =
    if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
        copy(
            busy = true,
            runtimeStartPhase = phase,
            runtimeStartSource = attempt.runtimeStartSource,
            runtimeStartProviderId = attempt.runtimeStartProviderId,
            runtimeProgressMessage = attempt.message,
        )
    } else {
        copy(
            busy = true,
            runtimeStatus = PrivilegeUiRuntimeStatus.STARTING,
            runtimeStartPhase = phase,
            runtimeStartSource = attempt.runtimeStartSource,
            runtimeStartProviderId = attempt.runtimeStartProviderId,
            serverInfo = null,
            adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
            runtimeProgressMessage = attempt.message,
        )
    }

internal fun PrivilegeUiState.finishRuntimeStartPreservingStatus(): PrivilegeUiState =
    copy(
        busy = false,
        runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
        runtimeStartSource = null,
        runtimeStartProviderId = null,
        runtimeProgressMessage = null,
    )

internal fun PrivilegeUiState.finishRuntimeStartDisconnected(): PrivilegeUiState =
    if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
        finishRuntimeStartPreservingStatus()
    } else {
        copy(
            busy = false,
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            runtimeStartSource = null,
            runtimeStartProviderId = null,
            serverInfo = null,
            adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
            runtimeProgressMessage = null,
        )
    }

internal fun PrivilegeUiState.finishRuntimeStartFailed(): PrivilegeUiState =
    if (runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED) {
        finishRuntimeStartPreservingStatus()
    } else {
        copy(
            busy = false,
            runtimeStatus = PrivilegeUiRuntimeStatus.FAILED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
            runtimeStartSource = null,
            runtimeStartProviderId = null,
            serverInfo = null,
            adbRestrictionStatus = PrivilegeUiAdbRestrictionStatus.UNKNOWN,
            runtimeProgressMessage = null,
        )
    }
