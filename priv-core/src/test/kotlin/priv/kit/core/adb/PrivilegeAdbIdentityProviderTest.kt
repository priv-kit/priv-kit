package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import priv.kit.core.PrivilegeStartupException

class PrivilegeAdbIdentityProviderTest {
    @Test
    fun identityInfoWrapsKeyLoadingFailureInPublicStartupException() {
        val keyFailure = IllegalStateException("key storage is unavailable")
        val provider = PrivilegeAdbIdentityProvider(
            identity = PrivilegeAdbIdentity.default(
                deviceName = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
            ),
            loadKeyBytes = { throw keyFailure },
        )

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            provider.getIdentityInfo()
        }

        assertEquals("Failed to load ADB identity", exception.message)
        assertSame(keyFailure, exception.cause?.cause)
    }
}
