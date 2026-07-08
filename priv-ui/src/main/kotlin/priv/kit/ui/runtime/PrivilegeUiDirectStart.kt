package priv.kit.ui.runtime

import priv.kit.ui.*
import priv.kit.ui.state.*

internal sealed interface PrivilegeUiDirectStartTarget {
    data object Adb : PrivilegeUiDirectStartTarget
    data object Root : PrivilegeUiDirectStartTarget
    data class External(val providerId: String) : PrivilegeUiDirectStartTarget
}

internal fun PrivilegeUiState.directStartTarget(
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    wirelessAdbSupported: Boolean = true,
    managedWirelessAdbEnabled: Boolean = true,
): PrivilegeUiDirectStartTarget? =
    directStartTargets(
        tcpModeEnabled = tcpModeEnabled,
        tcpPolicy = tcpPolicy,
        wirelessAdbSupported = wirelessAdbSupported,
        managedWirelessAdbEnabled = managedWirelessAdbEnabled,
    ).firstOrNull()

internal fun PrivilegeUiState.directStartTargets(
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    wirelessAdbSupported: Boolean = true,
    managedWirelessAdbEnabled: Boolean = true,
): List<PrivilegeUiDirectStartTarget> =
    buildList {
        directStartModeOrder().forEach { mode ->
            when (mode) {
                PrivilegeUiStartupMode.ADB -> {
                    if (
                        canStartAdbDirectly(
                            tcpModeEnabled,
                            tcpPolicy,
                            wirelessAdbSupported,
                            managedWirelessAdbEnabled,
                        )
                    ) {
                        add(PrivilegeUiDirectStartTarget.Adb)
                    }
                }
                PrivilegeUiStartupMode.EXTERNAL -> {
                    externalStartItems
                        .filter { it.snapshot.canStart }
                        .forEach { add(PrivilegeUiDirectStartTarget.External(it.id)) }
                }
                PrivilegeUiStartupMode.MANUAL_SHELL -> Unit
                PrivilegeUiStartupMode.ROOT -> add(PrivilegeUiDirectStartTarget.Root)
            }
        }
    }

internal fun PrivilegeUiState.directStartModeOrder(): List<PrivilegeUiStartupMode> =
    buildList {
        if (selectedStartupMode in startupModes) {
            add(selectedStartupMode)
        }
        startupModes.forEach { mode ->
            if (mode !in this) {
                add(mode)
            }
        }
    }

internal fun PrivilegeUiState.hasDirectStartTarget(
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    wirelessAdbSupported: Boolean = true,
    managedWirelessAdbEnabled: Boolean = true,
): Boolean {
    directStartModeOrder().forEach { mode ->
        when (mode) {
            PrivilegeUiStartupMode.ADB -> {
                if (
                    canStartAdbDirectly(
                        tcpModeEnabled,
                        tcpPolicy,
                        wirelessAdbSupported,
                        managedWirelessAdbEnabled,
                    )
                ) {
                    return true
                }
            }
            PrivilegeUiStartupMode.EXTERNAL -> {
                if (externalStartItems.any { it.snapshot.canStart }) {
                    return true
                }
            }
            PrivilegeUiStartupMode.MANUAL_SHELL -> Unit
            PrivilegeUiStartupMode.ROOT -> return true
        }
    }
    return false
}

internal fun PrivilegeUiState.canStartAdbDirectly(
    tcpModeEnabled: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    wirelessAdbSupported: Boolean = true,
    managedWirelessAdbEnabled: Boolean = true,
): Boolean {
    val paired = wirelessAdbSupported && wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.ON
    val knownUnpaired = wirelessPairingCheckStatus == PrivilegeUiWirelessAdbStatus.OFF
    val tcpAuthorized = tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
        tcpModeEnabled &&
        tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED
    val wirelessEndpointAvailable = wirelessAdbSupported &&
        wifiConnected &&
        wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.ON &&
        !knownUnpaired
    val managedWirelessAvailable = managedWirelessAdbEnabled &&
        wifiConnected &&
        managedWirelessAdbStatus == PrivilegeUiManagedWirelessAdbStatus.READY &&
        !knownUnpaired
    return (paired && wifiConnected) || tcpAuthorized || wirelessEndpointAvailable || managedWirelessAvailable
}
