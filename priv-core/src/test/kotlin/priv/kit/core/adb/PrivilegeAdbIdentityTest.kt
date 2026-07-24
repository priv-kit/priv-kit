package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeAdbIdentityTest {
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
