package priv.kit.ui.adb

import priv.kit.core.adb.PrivilegeAdbStartOptions
import priv.kit.core.adb.PrivilegeAdbWirelessDebuggingControl
import priv.kit.shared.hasPrivilegeAdbCertificateUnknownMessage
import priv.kit.shared.hasPrivilegeAdbKeyNotAuthorizedMessage
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus

internal fun Throwable.isAdbKeyNotAuthorizedFailure(): Boolean =
    hasPrivilegeAdbKeyNotAuthorizedMessage() ||
        generateSequence(this) { it.cause }.any { throwable ->
            throwable.message.orEmpty().hasPrivilegeAdbCertificateUnknownMessage()
        }

internal fun privilegeUiWirelessAdbStartOptions(
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    activeTcpPort: Int?,
    managedWirelessAdbEnabled: Boolean,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): PrivilegeAdbStartOptions =
    if (
        tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED &&
        activeTcpPort != null
    ) {
        PrivilegeAdbStartOptions(
            port = activeTcpPort,
        )
    } else {
        privilegeUiManagedWirelessAdbStartOptions(
            managedWirelessAdbEnabled = managedWirelessAdbEnabled,
            managedWirelessAdbStatus = managedWirelessAdbStatus,
        )
    }

internal fun privilegeUiStaticTcpSwitchOptions(
    managedWirelessAdbEnabled: Boolean,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): PrivilegeAdbStartOptions =
    privilegeUiManagedWirelessAdbStartOptions(
        managedWirelessAdbEnabled = managedWirelessAdbEnabled,
        managedWirelessAdbStatus = managedWirelessAdbStatus,
    )

private fun privilegeUiManagedWirelessAdbStartOptions(
    managedWirelessAdbEnabled: Boolean,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): PrivilegeAdbStartOptions =
    PrivilegeAdbStartOptions(
        wirelessDebuggingControl = managedWirelessAdbEnabled
            .withDeclaredManagedWirelessAdbStatus(managedWirelessAdbStatus)
            .toWirelessDebuggingControl(),
    )

private fun Boolean.withDeclaredManagedWirelessAdbStatus(
    status: PrivilegeUiManagedWirelessAdbStatus,
): Boolean =
    this && status != PrivilegeUiManagedWirelessAdbStatus.UNDECLARED

private fun Boolean.toWirelessDebuggingControl(): PrivilegeAdbWirelessDebuggingControl =
    if (this) {
        PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE
    } else {
        PrivilegeAdbWirelessDebuggingControl.NEVER
    }
