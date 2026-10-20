package priv.kit.evdev

/** One Linux input event. No timestamp or implicit SYN_REPORT is added to the batch. */
public data class PrivilegeEvdevEvent(public val type: Int, public val code: Int, public val value: Int) {
    init {
        require(type in 0..0x1f) { "Invalid Linux event type" }
        require(code in 0..0xffff) { "Invalid Linux event code" }
    }
}

/** A snapshot from EVIOCGABS; values are in device coordinates. */
public data class PrivilegeEvdevAxis(
    public val value: Int,
    public val minimum: Int,
    public val maximum: Int,
    public val fuzz: Int,
    public val flat: Int,
    public val resolution: Int,
)

/**
 * A write syscall result, not proof of delivery to a window. A null submittedEventCount means
 * the outcome is unknown. Never automatically replay a failed or unknown batch.
 */
public data class PrivilegeEvdevWriteResult(
    public val requestedEventCount: Int,
    public val submittedEventCount: Int?,
    public val errno: Int?,
) {
    public val isComplete: Boolean
        get() = submittedEventCount == requestedEventCount && errno == null
}
