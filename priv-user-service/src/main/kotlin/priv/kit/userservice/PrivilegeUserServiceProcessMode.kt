package priv.kit.userservice

enum class PrivilegeUserServiceProcessMode(
    internal val wireValue: Int,
) {
    DEDICATED_PROCESS(0),
    IN_SERVER_PROCESS(1),
    ;

    companion object {
        internal fun fromWireValue(value: Int): PrivilegeUserServiceProcessMode =
            entries.firstOrNull { it.wireValue == value } ?: DEDICATED_PROCESS
    }
}
