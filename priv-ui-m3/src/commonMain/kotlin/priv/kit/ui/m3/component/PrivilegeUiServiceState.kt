package priv.kit.ui.m3.component

import priv.kit.ui.*

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiPermissionRestrictionWarningVisible(
    runtimeStatus: PrivilegeUiRuntimeStatus,
    restrictionStatus: PrivilegeUiPermissionRestrictionStatus,
): Boolean =
    runtimeStatus == PrivilegeUiRuntimeStatus.CONNECTED &&
        restrictionStatus == PrivilegeUiPermissionRestrictionStatus.RESTRICTED


@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiAutoRecoveryWarningVisible(
    desiredEnabled: Boolean,
    runtimeStatus: PrivilegeUiRuntimeStatus,
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
): Boolean =
    desiredEnabled &&
        runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE &&
        (
            runtimeStatus == PrivilegeUiRuntimeStatus.DISCONNECTED ||
                runtimeStatus == PrivilegeUiRuntimeStatus.FAILED
        )


@priv.kit.ui.ExperimentalPrivilegeUiApi
public enum class PrivilegeUiServiceStatusAction {
    START,
    CANCEL,
    CANCELLING,
    STOP,
}

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiServiceStatusAction(
    runtimeStatus: PrivilegeUiRuntimeStatus,
    runtimeStartPhase: PrivilegeUiRuntimeStartPhase,
): PrivilegeUiServiceStatusAction =
    when (runtimeStartPhase) {
        PrivilegeUiRuntimeStartPhase.RUNNING -> PrivilegeUiServiceStatusAction.CANCEL
        PrivilegeUiRuntimeStartPhase.CANCELLING -> PrivilegeUiServiceStatusAction.CANCELLING
        PrivilegeUiRuntimeStartPhase.IDLE -> when (runtimeStatus) {
            PrivilegeUiRuntimeStatus.CONNECTED -> PrivilegeUiServiceStatusAction.STOP
            PrivilegeUiRuntimeStatus.STARTING,
            PrivilegeUiRuntimeStatus.DISCONNECTED,
            PrivilegeUiRuntimeStatus.FAILED,
            -> PrivilegeUiServiceStatusAction.START
        }
    }

@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiServiceStatusActionEnabled(
    action: PrivilegeUiServiceStatusAction,
    busy: Boolean,
): Boolean =
    when (action) {
        PrivilegeUiServiceStatusAction.CANCEL -> true
        PrivilegeUiServiceStatusAction.CANCELLING -> false
        PrivilegeUiServiceStatusAction.START,
        PrivilegeUiServiceStatusAction.STOP,
        -> !busy
    }


@priv.kit.ui.ExperimentalPrivilegeUiApi
public fun privilegeUiAutoRecoveryWarningVisible(
    state: PrivilegeUiScreenState,
    interactionEnabled: Boolean,
): Boolean = interactionEnabled &&
    privilegeUiAutoRecoveryWarningVisible(
        desiredEnabled = state.desiredEnabled,
        runtimeStatus = state.runtimeStatus,
        runtimeStartPhase = state.runtimeStartPhase,
    )

