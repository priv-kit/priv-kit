package priv.kit.core.internal.core

internal object PrivilegeProtocol {
    const val VERSION: Int = 12
    const val DEFAULT_FOLLOW_DEATH_DELAY_MILLIS: Long = 10 * 60 * 1000L
    const val DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH: Boolean = false
}
