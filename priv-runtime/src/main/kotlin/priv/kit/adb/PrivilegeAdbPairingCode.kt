package priv.kit.adb

internal fun String.toPrivilegeAdbPairingCode(): String =
    filter(Char::isDigit)
        .take(ADB_PAIRING_CODE_LENGTH)

private const val ADB_PAIRING_CODE_LENGTH = 6
