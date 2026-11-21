package priv.kit.ui.component

import priv.kit.ui.PrivilegeUiRuntimeStartPhase
import priv.kit.ui.PrivilegeUiRuntimeStartSource
import priv.kit.ui.PrivilegeUiScreenState

@priv.kit.ui.ExperimentalPrivilegeUiApi
public enum class PrivilegeUiStartAction {
    START,
    CANCEL,
    CANCELLING,
    NONE,
}

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiStartAction(
    phase: PrivilegeUiRuntimeStartPhase,
    ownsRuntimeStart: Boolean,
): PrivilegeUiStartAction =
    when (phase) {
        PrivilegeUiRuntimeStartPhase.IDLE -> PrivilegeUiStartAction.START
        PrivilegeUiRuntimeStartPhase.RUNNING -> if (ownsRuntimeStart) {
            PrivilegeUiStartAction.CANCEL
        } else {
            PrivilegeUiStartAction.NONE
        }
        PrivilegeUiRuntimeStartPhase.CANCELLING -> if (ownsRuntimeStart) {
            PrivilegeUiStartAction.CANCELLING
        } else {
            PrivilegeUiStartAction.NONE
        }
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun PrivilegeUiScreenState.startActionFor(
    source: PrivilegeUiRuntimeStartSource,
    providerId: String?,
): PrivilegeUiStartAction {
    val providerMatches = source != PrivilegeUiRuntimeStartSource.EXTERNAL ||
        (providerId != null && runtimeStartProviderId == providerId)
    val ownsRuntimeStart = runtimeStartSource == source && providerMatches
    return privilegeUiStartAction(runtimeStartPhase, ownsRuntimeStart)
}

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiStartActionEnabled(
    action: PrivilegeUiStartAction,
    startEnabled: Boolean,
): Boolean =
    when (action) {
        PrivilegeUiStartAction.START -> startEnabled
        PrivilegeUiStartAction.CANCEL -> true
        PrivilegeUiStartAction.CANCELLING,
        PrivilegeUiStartAction.NONE,
        -> false
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun PrivilegeUiScreenState.startActionEnabled(
    action: PrivilegeUiStartAction,
    startAvailable: Boolean,
): Boolean =
    privilegeUiStartActionEnabled(
        action = action,
        startEnabled = startAvailable && !busy,
    )
