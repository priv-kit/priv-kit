package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

internal fun PrivilegeUiViewModelStore.currentTcpModePort(): Int? =
    state.value.configuredTcpModePort
        .takeIf { config.adbTcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED }

internal fun PrivilegeUiViewModelStore.managedWirelessAdbEnabledForStart(): Boolean =
    config.enableManagedWirelessAdb &&
        state.value.managedWirelessAdbStatus != PrivilegeUiManagedWirelessAdbStatus.UNDECLARED

internal fun PrivilegeUiViewModelStore.updateTcpModePort(activeTcpPort: Int?) {
    tcpModeEnabled.value = activeTcpPort != null
    updateState {
        it.copy(tcpModePort = activeTcpPort)
    }
}

internal fun PrivilegeUiViewModelStore.updateConfiguredTcpModePort(configuredTcpPort: Int?) {
    updateState {
        it.copy(configuredTcpModePort = configuredTcpPort)
    }
}
