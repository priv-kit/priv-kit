package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbIdentityTest {
    @Test
    fun defaultIdentityUsesPrivKitName() {
        val identity = PrivilegeAdbIdentity.default(" Demo App ")

        assertEquals("Demo App", identity.adbDeviceName)
        assertEquals(PrivilegeAdbIdentity.DEFAULT_STORAGE_SIGNATURE, identity.storageSignature)
    }

    @Test
    fun ownerTokenIsTrimmedForKeyStorage() {
        val identity = PrivilegeAdbIdentity.forOwnerToken(
            ownerToken = " owner-token ",
            deviceName = " Demo App ",
        )

        assertEquals("Demo App", identity.adbDeviceName)
        assertEquals("owner-token", identity.storageSignature)
    }

    @Test(expected = IllegalArgumentException::class)
    fun blankOwnerTokenFails() {
        PrivilegeAdbIdentity.forOwnerToken(" ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun lineBreakInOwnerTokenFails() {
        PrivilegeAdbIdentity.forOwnerToken("priv\nkit")
    }

    @Test(expected = IllegalArgumentException::class)
    fun lineBreakInDeviceNameFails() {
        PrivilegeAdbIdentity.forOwnerToken(
            ownerToken = "owner-token",
            deviceName = "priv\nkit",
        )
    }
}
