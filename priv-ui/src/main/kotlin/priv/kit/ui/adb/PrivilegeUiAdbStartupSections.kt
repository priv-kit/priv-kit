package priv.kit.ui.adb

import priv.kit.ui.PrivilegeUiAdbTcpPolicy

internal enum class PrivilegeUiAdbStartupSection {
    WIRELESS,
    STATIC_TCP,
}

internal fun privilegeUiAdbStartupSections(
    wirelessAdbSupported: Boolean,
    tcpPolicy: PrivilegeUiAdbTcpPolicy,
): List<PrivilegeUiAdbStartupSection> =
    buildList {
        if (wirelessAdbSupported) {
            add(PrivilegeUiAdbStartupSection.WIRELESS)
        }
        if (tcpPolicy != PrivilegeUiAdbTcpPolicy.DISABLED) {
            add(PrivilegeUiAdbStartupSection.STATIC_TCP)
        }
    }
