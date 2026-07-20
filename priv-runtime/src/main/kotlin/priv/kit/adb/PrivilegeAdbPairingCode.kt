package priv.kit.adb

import priv.kit.shared.isPrivilegeAdbPairingCode
import priv.kit.shared.toPrivilegeAdbPairingCodeDigits

internal fun String.toPrivilegeAdbPairingCode(): String =
    toPrivilegeAdbPairingCodeDigits()
        .takeIf { it.isPrivilegeAdbPairingCode() }
        .orEmpty()
