package priv.kit.ui

import org.junit.Assert.assertThrows
import org.junit.Test

class PrivilegeUiConfigTest {
    @Test
    fun notificationPairingConfigIsValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingChannelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingNotificationId = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUiConfig(notificationPairingNotificationId = Int.MAX_VALUE)
        }
    }
}
