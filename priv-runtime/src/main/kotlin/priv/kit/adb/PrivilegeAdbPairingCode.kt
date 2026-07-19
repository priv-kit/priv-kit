package priv.kit.adb

internal fun String.toPrivilegeAdbPairingCode(): String =
    filter { it in '0'..'9' }
        .take(ADB_PAIRING_CODE_LENGTH)
        .takeIf { it.length == ADB_PAIRING_CODE_LENGTH }
        .orEmpty()

private const val ADB_PAIRING_CODE_LENGTH = 6
