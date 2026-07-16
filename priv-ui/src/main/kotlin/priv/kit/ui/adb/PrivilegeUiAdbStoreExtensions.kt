package priv.kit.ui.adb

import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus
import priv.kit.ui.state.PrivilegeUiViewModelStore

internal fun PrivilegeUiViewModelStore.currentTcpModePort(): Int? =
    state.value.configuredTcpModePort
        .takeIf { config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED }

internal fun PrivilegeUiViewModelStore.managedWirelessAdbEnabledForStart(): Boolean =
    config.enableManagedWirelessAdb &&
        state.value.managedWirelessAdbStatus != PrivilegeUiManagedWirelessAdbStatus.UNDECLARED

internal fun PrivilegeUiViewModelStore.updateTcpModePort(activeTcpPort: Int?) {
    updateState {
        it.copy(tcpModePort = activeTcpPort)
    }
}

internal fun PrivilegeUiViewModelStore.updateConfiguredTcpModePort(configuredTcpPort: Int?) {
    updateState {
        it.copy(configuredTcpModePort = configuredTcpPort)
    }
}
