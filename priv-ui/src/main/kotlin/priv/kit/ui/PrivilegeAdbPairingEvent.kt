package priv.kit.ui

internal data class PrivilegeAdbPairingEvent(
    val type: PrivilegeAdbPairingEventType,
    val message: String,
    val running: Boolean,
    val port: Int? = null,
    val adbDeviceName: String? = null,
    val fingerprint: String? = null,
)

internal enum class PrivilegeAdbPairingEventType {
    SEARCHING,
    FOUND,
    PAIRING,
    PAIRED,
    FAILED,
    STOPPED,
}
