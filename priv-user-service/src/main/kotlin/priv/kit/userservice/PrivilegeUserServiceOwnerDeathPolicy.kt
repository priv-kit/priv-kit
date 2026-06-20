package priv.kit.userservice

enum class PrivilegeUserServiceOwnerDeathPolicy(
    internal val wireValue: Int,
) {
    DESTROY_ON_OWNER_DEATH(0),
    KEEP_UNTIL_SERVER_EXIT(1),
    ;

    companion object {
        internal fun fromWireValue(value: Int): PrivilegeUserServiceOwnerDeathPolicy =
            entries.firstOrNull { it.wireValue == value } ?: DESTROY_ON_OWNER_DEATH
    }
}
