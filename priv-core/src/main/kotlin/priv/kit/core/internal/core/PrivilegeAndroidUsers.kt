package priv.kit.core.internal.core

internal object PrivilegeAndroidUsers {
    const val USER_SYSTEM: Int = 0

    fun userIdFromUid(uid: Int): Int =
        if (uid >= PER_USER_RANGE) uid / PER_USER_RANGE else USER_SYSTEM

    private const val PER_USER_RANGE = 100_000
}
