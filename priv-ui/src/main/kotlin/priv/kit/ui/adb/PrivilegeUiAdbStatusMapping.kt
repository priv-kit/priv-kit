package priv.kit.ui.adb

import priv.kit.ui.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import priv.kit.adb.PrivilegeAdbWirelessDebuggingControlStatus

internal fun privilegeUiPairingCheckStatus(
    wirelessDebuggingOn: Boolean,
    pairingCheckPaired: Boolean?,
    currentStatus: PrivilegeUiWirelessAdbStatus,
): PrivilegeUiWirelessAdbStatus =
    when {
        !wirelessDebuggingOn -> PrivilegeUiWirelessAdbStatus.UNKNOWN
        pairingCheckPaired != null -> if (pairingCheckPaired) {
            PrivilegeUiWirelessAdbStatus.ON
        } else {
            PrivilegeUiWirelessAdbStatus.OFF
        }
        currentStatus == PrivilegeUiWirelessAdbStatus.ON -> PrivilegeUiWirelessAdbStatus.ON
        else -> PrivilegeUiWirelessAdbStatus.UNKNOWN
    }

internal fun privilegeUiWirelessDebuggingStatus(
    wirelessDebuggingEnabled: Boolean,
    connectPortAvailable: Boolean,
    pairingServiceOn: Boolean,
): PrivilegeUiWirelessAdbStatus =
    if (wirelessDebuggingEnabled) {
        PrivilegeUiWirelessAdbStatus.ON
    } else {
        PrivilegeUiWirelessAdbStatus.OFF
    }

internal fun privilegeUiWirelessDebuggingStatus(
    wirelessDebuggingEnabled: Boolean,
): PrivilegeUiWirelessAdbStatus =
    privilegeUiWirelessDebuggingStatus(
        wirelessDebuggingEnabled = wirelessDebuggingEnabled,
        connectPortAvailable = false,
        pairingServiceOn = false,
    )

internal fun privilegeUiRefreshingPairingCheckStatus(
    wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
    currentStatus: PrivilegeUiWirelessAdbStatus,
): PrivilegeUiWirelessAdbStatus =
    when {
        wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.OFF -> PrivilegeUiWirelessAdbStatus.UNKNOWN
        currentStatus == PrivilegeUiWirelessAdbStatus.ON -> PrivilegeUiWirelessAdbStatus.ON
        else -> PrivilegeUiWirelessAdbStatus.CHECKING
    }

internal fun shouldRequireWirelessPairingForStart(
    pairingCheckStatus: PrivilegeUiWirelessAdbStatus,
): Boolean =
    pairingCheckStatus == PrivilegeUiWirelessAdbStatus.OFF

internal fun shouldRequireWirelessDebuggingForStart(
    wirelessDebuggingStatus: PrivilegeUiWirelessAdbStatus,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): Boolean =
    wirelessDebuggingStatus != PrivilegeUiWirelessAdbStatus.ON &&
        managedWirelessAdbStatus != PrivilegeUiManagedWirelessAdbStatus.READY &&
        (
            wirelessDebuggingStatus == PrivilegeUiWirelessAdbStatus.OFF ||
                managedWirelessAdbStatus == PrivilegeUiManagedWirelessAdbStatus.PERMISSION_REQUIRED ||
                managedWirelessAdbStatus == PrivilegeUiManagedWirelessAdbStatus.UNDECLARED
            )

internal fun PrivilegeAdbWirelessDebuggingControlStatus.toUiManagedWirelessAdbStatus():
    PrivilegeUiManagedWirelessAdbStatus =
    when {
        !supported -> PrivilegeUiManagedWirelessAdbStatus.UNSUPPORTED
        !permissionDeclared -> PrivilegeUiManagedWirelessAdbStatus.UNDECLARED
        canManage -> PrivilegeUiManagedWirelessAdbStatus.READY
        failureMessage != null -> PrivilegeUiManagedWirelessAdbStatus.FAILED
        !permissionGranted -> PrivilegeUiManagedWirelessAdbStatus.PERMISSION_REQUIRED
        else -> PrivilegeUiManagedWirelessAdbStatus.UNKNOWN
    }

internal fun PrivilegeUiState.withWirelessAdbOffline(
    wifiConnected: Boolean = this.wifiConnected,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus = this.managedWirelessAdbStatus,
    notificationPairingRunning: Boolean = this.notificationPairingRunning,
): PrivilegeUiState =
    copy(
        wifiConnected = wifiConnected,
        wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
        wirelessPairingServiceStatus = PrivilegeUiWirelessAdbStatus.OFF,
        wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
        managedWirelessAdbStatus = managedWirelessAdbStatus,
        notificationPairingRunning = notificationPairingRunning,
    )
