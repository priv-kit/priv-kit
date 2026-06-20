package priv.kit.userservice

enum class PrivilegeUserServiceState(
    internal val wireValue: Int,
) {
    STOPPED(0),
    RUNNING(1),
    DESTROYED(2),
    FAILED(3),
    ;

    companion object {
        internal fun fromWireValue(value: Int): PrivilegeUserServiceState =
            entries.firstOrNull { it.wireValue == value } ?: STOPPED
    }
}
