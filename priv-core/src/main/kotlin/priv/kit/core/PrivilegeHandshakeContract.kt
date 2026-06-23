package priv.kit.core

public object PrivilegeHandshakeContract {
    public const val METHOD_SERVER_START_TOKEN: String = "privilege_server_start_token"
    public const val METHOD_SERVER_READY: String = "privilege_server_ready"

    public const val EXTRA_TOKEN: String = "privilege_token"
    public const val EXTRA_SERVER_BINDER: String = "privilege_server_binder"
    public const val EXTRA_UID: String = "privilege_uid"
    public const val EXTRA_PID: String = "privilege_pid"
    public const val EXTRA_LAUNCH_MODE: String = "privilege_launch_mode"
    public const val EXTRA_PROTOCOL_VERSION: String = "privilege_protocol_version"
    public const val EXTRA_SERVER_VERSION: String = "privilege_server_version"
    public const val EXTRA_CLASSPATH_IDENTITY: String = "privilege_classpath_identity"

    public const val RESULT_ACCEPTED: String = "privilege_accepted"
    public const val RESULT_TOKEN: String = "privilege_token"
    public const val RESULT_SHOULD_SHUTDOWN: String = "privilege_should_shutdown"
    public const val RESULT_RESTART_COMMAND_LINE: String = "privilege_restart_command_line"
    public const val RESULT_OWNER_BINDER: String = "privilege_owner_binder"
    public const val RESULT_FOLLOW_DEATH_DELAY_MILLIS: String = "privilege_follow_death_delay_millis"
    public const val RESULT_ACTIVE_RECONNECT_ON_OWNER_DEATH: String = "privilege_active_reconnect_on_owner_death"

    public fun providerAuthority(packageName: String): String = "$packageName.privilege.handshake"
}
