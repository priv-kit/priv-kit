package priv.kit.ui.adb

import priv.kit.adb.PrivilegeAdbStartOptions
import priv.kit.adb.PrivilegeAdbWirelessDebuggingControl
import priv.kit.ui.PrivilegeUiAdbTcpPolicy
import priv.kit.ui.PrivilegeUiManagedWirelessAdbStatus

internal fun Throwable.isAdbKeyNotAuthorizedFailure(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        val message = throwable.message.orEmpty()
        message.contains("ADB key is not authorized") ||
            message.contains("CERTIFICATE_UNKNOWN", ignoreCase = true) ||
            message.contains("certificate unknown", ignoreCase = true)
    }

internal fun privilegeUiWirelessAdbStartOptions(
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
    tcpPort: Int,
    activeTcpPort: Int? = null,
    managedWirelessAdbEnabled: Boolean = true,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
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
    managedWirelessAdbEnabled: Boolean = true,
    managedWirelessAdbStatus: PrivilegeUiManagedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
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
