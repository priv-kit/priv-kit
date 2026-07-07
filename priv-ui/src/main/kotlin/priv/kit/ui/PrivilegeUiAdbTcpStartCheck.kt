package priv.kit.ui

import priv.kit.adb.PrivilegeAdbAuthorizationStatus

internal sealed interface PrivilegeUiStaticTcpStartCheck {
    data class Ready(val tcpPort: Int) : PrivilegeUiStaticTcpStartCheck
    data object Failed : PrivilegeUiStaticTcpStartCheck
}

internal fun privilegeUiStaticTcpStartCheck(
    activeTcpPort: Int?,
    authorizationStatus: PrivilegeAdbAuthorizationStatus?,
): PrivilegeUiStaticTcpStartCheck =
    if (activeTcpPort != null && authorizationStatus == PrivilegeAdbAuthorizationStatus.AUTHORIZED) {
        PrivilegeUiStaticTcpStartCheck.Ready(activeTcpPort)
    } else {
        PrivilegeUiStaticTcpStartCheck.Failed
    }
