package priv.kit

import priv.kit.internal.core.PrivilegeProtocol

public object PrivilegeConfig {
    private val lock = Any()
    private var current = PrivilegeConfigSnapshot()

    public var followDeathDelayMillis: Long
        get() = snapshot().followDeathDelayMillis
        set(value) {
            val activeReconnect = activeReconnectOnOwnerDeath
            configure(
                followDeathDelayMillis = value,
                activeReconnectOnOwnerDeath = activeReconnect,
            )
        }

    public var activeReconnectOnOwnerDeath: Boolean
        get() = snapshot().activeReconnectOnOwnerDeath
        set(value) {
            val followDeathDelay = followDeathDelayMillis
            configure(
                followDeathDelayMillis = followDeathDelay,
                activeReconnectOnOwnerDeath = value,
            )
        }

    public fun configure(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        val next = PrivilegeConfigSnapshot(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        synchronized(lock) {
            current = next
        }
    }

    internal fun snapshot(): PrivilegeConfigSnapshot =
        synchronized(lock) {
            current
        }
}

internal data class PrivilegeConfigSnapshot(
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
) {
    init {
        require(followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
    }
}
