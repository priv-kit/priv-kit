package priv.kit.core

import priv.kit.core.internal.core.PrivilegeProtocol

public object PrivilegeConfig {
    private val lock = Any()
    private var current = PrivilegeConfigSnapshot()

    /**
     * How long the server remains alive while waiting for its owner process to reconnect.
     *
     * Changes are sent to the connected server and apply to the next owner death. A reconnect
     * flow that has already started continues with the snapshot it captured when the owner died.
     */
    public var followDeathDelayMillis: Long
        get() = snapshot().followDeathDelayMillis
        set(value) {
            update { current ->
                current.copy(followDeathDelayMillis = value)
            }
        }

    /**
     * Whether the server directly retries its owner provider after the owner process dies.
     *
     * Changes are sent to the connected server and apply to the next owner death. A reconnect
     * flow that has already started continues with the snapshot it captured when the owner died.
     */
    public var activeReconnectOnOwnerDeath: Boolean
        get() = snapshot().activeReconnectOnOwnerDeath
        set(value) {
            update { current ->
                current.copy(activeReconnectOnOwnerDeath = value)
            }
        }

    /** Atomically replaces and publishes the complete owner-death configuration. */
    public fun configure(
        followDeathDelayMillis: Long,
        activeReconnectOnOwnerDeath: Boolean,
    ) {
        update {
            PrivilegeConfigSnapshot(
                followDeathDelayMillis = followDeathDelayMillis,
                activeReconnectOnOwnerDeath = activeReconnectOnOwnerDeath,
            )
        }
    }

    internal fun snapshot(): PrivilegeConfigSnapshot =
        synchronized(lock) {
            current
        }

    private inline fun update(
        transform: (PrivilegeConfigSnapshot) -> PrivilegeConfigSnapshot,
    ) {
        synchronized(lock) {
            current = transform(current)
        }
        Privilege.updateRuntimeConfig()
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
