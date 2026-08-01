package priv.kit.ui.adb.pairing

import android.app.Notification
import org.junit.Assert.assertFalse
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
        val factory = PrivilegeAdbPairingNotificationFactory(RuntimeEnvironment.getApplication())

        assertTrue(factory.statusNotification(text = "Ready").hasRemoteInputAction())
        assertFalse(factory.workingNotification(text = "Working").hasRemoteInputAction())
    }

    private fun Notification.hasRemoteInputAction(): Boolean =
        actions?.any { !it.remoteInputs.isNullOrEmpty() } == true
}
