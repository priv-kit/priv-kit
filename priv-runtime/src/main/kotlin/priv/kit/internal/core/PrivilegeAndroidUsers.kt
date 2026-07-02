package priv.kit.internal.core

internal object PrivilegeAndroidUsers {
    const val USER_SYSTEM: Int = 0
    const val ROOT_UID: Int = 0
    const val SYSTEM_UID: Int = 1000
    const val SHELL_UID: Int = 2000

    fun userIdFromUid(uid: Int): Int =
        if (uid >= PER_USER_RANGE) uid / PER_USER_RANGE else USER_SYSTEM

    private const val PER_USER_RANGE = 100_000
}
