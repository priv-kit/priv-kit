package priv.kit

import priv.kit.internal.core.PrivilegeProtocol

public object PrivilegeRuntimeConfig {
    private val lock = Any()
    private var current = PrivilegeRuntimeConfigSnapshot()

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
        val next = PrivilegeRuntimeConfigSnapshot(
            followDeathDelayMillis = followDeathDelayMillis,
            activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
        )
        synchronized(lock) {
            current = next
        }
    }

    internal fun snapshot(): PrivilegeRuntimeConfigSnapshot =
        synchronized(lock) {
            current
        }
}

internal data class PrivilegeRuntimeConfigSnapshot(
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
) {
    init {
        require(followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
    }
}
