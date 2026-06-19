package priv.kit.core

object PrivilegeHandshakeContract {
    const val METHOD_SERVER_READY = "privilege_server_ready"

    const val EXTRA_TOKEN = "privilege_token"
    const val EXTRA_SERVER_BINDER = "privilege_server_binder"
    const val EXTRA_UID = "privilege_uid"
    const val EXTRA_PID = "privilege_pid"
    const val EXTRA_MODE = "privilege_mode"
    const val EXTRA_PROTOCOL_VERSION = "privilege_protocol_version"
    const val EXTRA_SERVER_VERSION = "privilege_server_version"

    const val RESULT_ACCEPTED = "privilege_accepted"
    const val RESULT_SHOULD_SHUTDOWN = "privilege_should_shutdown"
    const val RESULT_RESTART_COMMAND_LINE = "privilege_restart_command_line"
    const val RESULT_OWNER_BINDER = "privilege_owner_binder"
    const val RESULT_FOLLOW_DEATH_DELAY_MILLIS = "privilege_follow_death_delay_millis"
    const val RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH = "privilege_active_reconnect_on_owner_death"

    fun providerAuthority(packageName: String): String = "$packageName.privilege.handshake"
}
