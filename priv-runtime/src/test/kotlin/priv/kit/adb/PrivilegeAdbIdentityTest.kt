package priv.kit.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbIdentityTest {
    @Test
    fun defaultIdentityUsesPrivKitName() {
        val identity = PrivilegeAdbIdentity.default(" Demo App ")

        assertEquals("DemoApp", identity.adbDeviceName)
    }

    @Test
    fun ownerTokenIdentityUsesDeviceName() {
        val identity = PrivilegeAdbIdentity.forOwnerToken(
            ownerToken = " owner-token ",
            deviceName = " Demo App ",
        )

        assertEquals("DemoApp", identity.adbDeviceName)
    }

    @Test
    fun whitespaceInDeviceNameIsRemovedForAdbName() {
        val identity = PrivilegeAdbIdentity.default("Priv\tKit Sample")

        assertEquals("PrivKitSample", identity.adbDeviceName)
        assertEquals("Priv\tKit Sample", identity.deviceName)
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
