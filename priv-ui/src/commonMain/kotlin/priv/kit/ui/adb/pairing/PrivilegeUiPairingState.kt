package priv.kit.ui.adb.pairing

import priv.kit.ui.PrivilegeUiAdbPairingStatus

public fun PrivilegeUiAdbPairingStatus.isPrivilegeUiPairingSessionActive(): Boolean =
    this == PrivilegeUiAdbPairingStatus.SEARCHING ||
        this == PrivilegeUiAdbPairingStatus.FOUND ||
        this == PrivilegeUiAdbPairingStatus.PAIRING ||
        this == PrivilegeUiAdbPairingStatus.FAILED

public fun String.isPrivilegeUiPairingCode(): Boolean =
    length == 6 && all { it in '0'..'9' }
