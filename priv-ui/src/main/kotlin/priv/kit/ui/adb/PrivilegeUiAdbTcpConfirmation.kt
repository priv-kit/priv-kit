package priv.kit.ui.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public enum class PrivilegeUiStaticTcpSwitchAction {
    START_SERVICE,
    ENABLE_PORT,
}

internal enum class PrivilegeUiStaticTcpSwitchConsent {
    REQUEST_CONFIRMATION,
    APPROVED,
    DO_NOT_SWITCH,
}

internal enum class PrivilegeUiStaticTcpSwitchDecision {
    REQUEST_CONFIRMATION,
    SWITCH,
    SKIP,
}

internal fun PrivilegeUiStaticTcpSwitchConsent.toDecision(): PrivilegeUiStaticTcpSwitchDecision =
    when (this) {
        PrivilegeUiStaticTcpSwitchConsent.REQUEST_CONFIRMATION ->
            PrivilegeUiStaticTcpSwitchDecision.REQUEST_CONFIRMATION
        PrivilegeUiStaticTcpSwitchConsent.APPROVED ->
            PrivilegeUiStaticTcpSwitchDecision.SWITCH
        PrivilegeUiStaticTcpSwitchConsent.DO_NOT_SWITCH ->
            PrivilegeUiStaticTcpSwitchDecision.SKIP
    }

internal class PrivilegeUiStaticTcpConfirmationController {
    private val lock = Any()
    private val pendingActionState = MutableStateFlow<PrivilegeUiStaticTcpSwitchAction?>(null)

    val pendingAction: StateFlow<PrivilegeUiStaticTcpSwitchAction?> =
        pendingActionState.asStateFlow()

    fun request(action: PrivilegeUiStaticTcpSwitchAction) {
        synchronized(lock) {
            pendingActionState.value = when {
                pendingActionState.value == PrivilegeUiStaticTcpSwitchAction.START_SERVICE ->
                    PrivilegeUiStaticTcpSwitchAction.START_SERVICE
                action == PrivilegeUiStaticTcpSwitchAction.START_SERVICE ->
                    PrivilegeUiStaticTcpSwitchAction.START_SERVICE
                else -> action
            }
        }
    }

    fun take(): PrivilegeUiStaticTcpSwitchAction? =
        synchronized(lock) {
            pendingActionState.value.also {
                pendingActionState.value = null
            }
        }

    fun cancel() {
        synchronized(lock) {
            pendingActionState.value = null
        }
    }
}
