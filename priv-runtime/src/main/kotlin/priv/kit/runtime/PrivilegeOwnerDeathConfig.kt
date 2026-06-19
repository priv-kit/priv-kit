package priv.kit.runtime

import android.content.Context
import priv.kit.core.PrivilegeProtocol

internal data class PrivilegeOwnerDeathConfig(
    val followDeathDelayMillis: Long = PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
    val activeReconnectOnOwnerDeath: Boolean = PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
)

internal class PrivilegeOwnerDeathConfigStore(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun read(): PrivilegeOwnerDeathConfig =
        synchronized(lock) {
            PrivilegeOwnerDeathConfig(
                followDeathDelayMillis = preferences.getLong(
                    KEY_FOLLOW_DEATH_DELAY_MILLIS,
                    PrivilegeProtocol.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS,
                ),
                activeReconnectOnOwnerDeath = preferences.getBoolean(
                    KEY_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                    PrivilegeProtocol.DEFAULT_ACTIVE_RECONNECT_ON_OWNER_DEATH,
                ),
            )
        }

    fun write(config: PrivilegeOwnerDeathConfig) {
        require(config.followDeathDelayMillis >= 0L) { "followDeathDelayMillis must not be negative" }
        synchronized(lock) {
            val committed = preferences.edit()
                .putLong(KEY_FOLLOW_DEATH_DELAY_MILLIS, config.followDeathDelayMillis)
                .putBoolean(KEY_ACTIVE_RECONNECT_ON_OWNER_DEATH, config.activeReconnectOnOwnerDeath)
                .commit()
            check(committed) { "Failed to write owner death config" }
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "priv-kit-owner-death"
        private const val KEY_FOLLOW_DEATH_DELAY_MILLIS = "follow_death_delay_millis"
        private const val KEY_ACTIVE_RECONNECT_ON_OWNER_DEATH = "active_reconnect_on_owner_death"
        private val lock = Any()
    }
}
