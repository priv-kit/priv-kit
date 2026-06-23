package priv.kit.userservice

public enum class PrivilegeUserServiceProcessMode(
    internal val wireValue: Int,
) {
    DEDICATED_PROCESS(0),
    IN_SERVER_PROCESS(1),
    ;

    internal companion object {
        internal fun fromWireValue(value: Int): PrivilegeUserServiceProcessMode =
            entries.firstOrNull { it.wireValue == value } ?: DEDICATED_PROCESS
    }
}
