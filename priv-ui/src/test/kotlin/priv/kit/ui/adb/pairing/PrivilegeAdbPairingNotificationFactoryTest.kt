package priv.kit.ui.adb.pairing

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbPairingNotificationFactoryTest {
    @Test
    fun workingNotificationAcknowledgesRemoteInputWithoutOfferingAnotherReply() {
        val factory = PrivilegeAdbPairingNotificationFactory(
            context = RuntimeEnvironment.getApplication(),
            notificationSpec = PrivilegeAdbPairingNotificationSpec.Default,
        )

        assertTrue(factory.statusNotification(text = "Ready").hasRemoteInputAction())
        assertFalse(factory.workingNotification(text = "Working").hasRemoteInputAction())
    }

    @Test
    fun customChannelIdIsUsedForCreationAndNotifications() {
        val context = RuntimeEnvironment.getApplication()
        val channelId = "host_pairing"
        val factory = PrivilegeAdbPairingNotificationFactory(
            context = context,
            notificationSpec = PrivilegeAdbPairingNotificationSpec(
                channelId = channelId,
                notificationId = 450,
            ),
        )

        factory.ensureNotificationChannel()

        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel(channelId))
        assertEquals(channelId, factory.statusNotification(text = "Ready").channelId)
    }

    private fun Notification.hasRemoteInputAction(): Boolean =
        actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
}
