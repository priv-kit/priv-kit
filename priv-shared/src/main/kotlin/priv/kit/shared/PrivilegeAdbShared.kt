package priv.kit.shared

/** IPv4 loopback address used for local ADB transport connections. */
public const val PRIVILEGE_INTERNAL_ADB_LOOPBACK_HOST: String = "127.0.0.1"

/** Default port used by Android's ADB TCP mode. */
public const val PRIVILEGE_INTERNAL_DEFAULT_ADB_TCP_PORT: Int = 5555

/** Number of ASCII digits in a Wireless Debugging pairing code. */
public const val PRIVILEGE_INTERNAL_ADB_PAIRING_CODE_LENGTH: Int = 6

/** Default timeout for starting the Priv Kit server. */
public const val PRIVILEGE_INTERNAL_DEFAULT_START_TIMEOUT_MILLIS: Long = 15_000L

/** Default timeout for an interactive ADB authorization request. */
public const val PRIVILEGE_INTERNAL_DEFAULT_ADB_AUTHORIZATION_TIMEOUT_MILLIS: Long = 60_000L

/** Maximum ADB device-name length accepted by Priv Kit. */
public const val PRIVILEGE_INTERNAL_MAX_ADB_DEVICE_NAME_LENGTH: Int = 128

/** Returns whether this value is a valid ADB endpoint port number. */
public fun Int.isPrivilegeAdbPort(): Boolean = this in 1..65_535

/** Keeps only the first Wireless Debugging pairing-code ASCII digits in this text. */
public fun String.toPrivilegeAdbPairingCodeDigits(): String =
    filter { it in '0'..'9' }
        .take(PRIVILEGE_INTERNAL_ADB_PAIRING_CODE_LENGTH)

/** Returns whether this value is exactly one Wireless Debugging pairing code. */
public fun String.isPrivilegeAdbPairingCode(): Boolean =
    length == PRIVILEGE_INTERNAL_ADB_PAIRING_CODE_LENGTH && all { it in '0'..'9' }

/** Sanitizes editable text before it is used as a Priv Kit ADB device name. */
public fun String.toPrivilegeAdbDeviceNameText(): String =
    replace('\u0000', ' ')
        .replace('\r', ' ')
        .replace('\n', ' ')
        .trim()
        .take(PRIVILEGE_INTERNAL_MAX_ADB_DEVICE_NAME_LENGTH)

/** Returns whether this throwable chain contains Priv Kit's ADB authorization failure message. */
public fun Throwable.hasPrivilegeAdbKeyNotAuthorizedMessage(): Boolean =
    generateSequence(this) { it.cause }.any { throwable ->
        throwable.message.orEmpty().contains(ADB_KEY_NOT_AUTHORIZED_MESSAGE)
    }

/** Returns whether this text contains either common TLS certificate-unknown spelling. */
public fun String.hasPrivilegeAdbCertificateUnknownMessage(): Boolean =
    contains(ADB_CERTIFICATE_UNKNOWN_UNDERSCORE, ignoreCase = true) ||
        contains(ADB_CERTIFICATE_UNKNOWN_SPACE, ignoreCase = true)

private const val ADB_KEY_NOT_AUTHORIZED_MESSAGE = "ADB key is not authorized"
private const val ADB_CERTIFICATE_UNKNOWN_UNDERSCORE = "CERTIFICATE_UNKNOWN"
private const val ADB_CERTIFICATE_UNKNOWN_SPACE = "certificate unknown"
