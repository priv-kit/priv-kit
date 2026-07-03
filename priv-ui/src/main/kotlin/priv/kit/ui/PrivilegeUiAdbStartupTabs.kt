package priv.kit.ui

internal enum class PrivilegeUiAdbStartupTab {
    WIRELESS,
    STATIC_TCP,
}

internal fun privilegeUiAdbStartupTabs(
    wirelessAdbSupported: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
): List<PrivilegeUiAdbStartupTab> =
    buildList {
        if (wirelessAdbSupported) {
            add(PrivilegeUiAdbStartupTab.WIRELESS)
        }
        if (tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
            add(PrivilegeUiAdbStartupTab.STATIC_TCP)
        }
    }

internal fun defaultPrivilegeUiAdbStartupTab(
    tabs: List<PrivilegeUiAdbStartupTab>,
    tcpModeEnabled: Boolean,
    tcpAuthorizationStatus: PrivilegeUiAdbTcpAuthorizationStatus,
): PrivilegeUiAdbStartupTab? =
    if (
        PrivilegeUiAdbStartupTab.STATIC_TCP in tabs &&
        (tcpModeEnabled || tcpAuthorizationStatus == PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZED)
    ) {
        PrivilegeUiAdbStartupTab.STATIC_TCP
    } else {
        tabs.firstOrNull()
    }
