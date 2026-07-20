package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbIdentityTest {
    @Test
    fun identityUsesDeviceName() {
        val identity = PrivilegeAdbIdentity.default(deviceName = " Demo App ")

        assertEquals("DemoApp", identity.adbDeviceName)
    }

    @Test
    fun whitespaceInDeviceNameIsRemovedForAdbName() {
        val identity = PrivilegeAdbIdentity.default("Priv\tKit Sample")

        assertEquals("PrivKitSample", identity.adbDeviceName)
        assertEquals("Priv\tKit Sample", identity.deviceName)
    }

    @Test(expected = IllegalArgumentException::class)
    fun lineBreakInDeviceNameFails() {
        PrivilegeAdbIdentity.default(deviceName = "priv\nkit")
    }
}
