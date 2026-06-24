package priv.kit.runtime

import priv.kit.core.PrivilegeProtocol

internal data class PrivilegeRuntimeConfig(
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
) {
    init {
        require(followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
    }
}
