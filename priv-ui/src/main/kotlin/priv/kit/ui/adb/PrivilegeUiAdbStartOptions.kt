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
    tcpPort: Int,
    activeTcpPort: Int?,
    managedWirelessAdbEnabled: Boolean,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): PrivilegeAdbStartOptions =
    when {
        tcpPolicy == PrivilegeUiAdbTcpPolicy.DISABLED -> PrivilegeAdbStartOptions(
            wirelessDebuggingControl = managedWirelessAdbEnabled
                .withDeclaredManagedWirelessAdbStatus(managedWirelessAdbStatus)
                .toWirelessDebuggingControl(),
        )
        activeTcpPort != null -> PrivilegeAdbStartOptions(
            port = activeTcpPort,
            discoverPort = false,
            tcpPort = tcpPort,
            wirelessDebuggingControl = PrivilegeAdbWirelessDebuggingControl.NEVER,
        )
        else -> PrivilegeAdbStartOptions(
            tcpPort = tcpPort,
            discoverPort = true,
            wirelessDebuggingControl = managedWirelessAdbEnabled
                .withDeclaredManagedWirelessAdbStatus(managedWirelessAdbStatus)
                .toWirelessDebuggingControl(),
        )
    }

internal fun privilegeUiStaticTcpSwitchOptions(
    tcpPort: Int,
    managedWirelessAdbEnabled: Boolean,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus,
): PrivilegeAdbStartOptions =
    PrivilegeAdbStartOptions(
        tcpPort = tcpPort,
        discoverPort = true,
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
