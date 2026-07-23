package priv.kit.core.adb

import priv.kit.core.PrivilegeStartupException

internal class PrivilegeAdbIdentityProvider(
    val identity: PrivilegeAdbIdentity,
    private val loadKeyBytes: () -> ByteArray,
) {
    @Throws(PrivilegeStartupException::class)
    fun getIdentityInfo(): PrivilegeAdbIdentityInfo =
        try {
            val key = loadKey()
            PrivilegeAdbIdentityInfo(
                identity = identity,
                publicKeyFingerprint = key.adbPublicKeyFingerprint,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            if (throwable is PrivilegeStartupException) throw throwable
            throw PrivilegeStartupException("Failed to load ADB identity", throwable)
        }

    fun loadKey(): PrivilegeAdbKey =
        try {
            PrivilegeAdbKey(
                keyBytes = loadKeyBytes(),
                name = identity.adbDeviceName,
            )
        } catch (throwable: Throwable) {
            throwable.rethrowIfInterrupted()
            throw PrivilegeAdbException("Failed to load ADB key", throwable)
        }
}
