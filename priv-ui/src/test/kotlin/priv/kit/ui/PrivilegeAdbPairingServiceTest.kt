package priv.kit.ui

import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingIntentContract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeAdbPairingServiceTest {
    @Test
    fun stopWithoutRemoteInputStopsImmediately() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val ownerId = "ordinary-owner"
        service.onStartCommand(
            Intent(service, PrivilegeAdbPairingService::class.java)
                .setAction(PrivilegeAdbPairingIntentContract.ACTION_START)
                .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_OWNER_ID, ownerId),
            0,
            1,
        )

        try {
            PrivilegeAdbPairingService.stop(service, ownerId)

            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stopAfterRemoteInputWaitsForSystemUiToReleaseItsLifetimeExtension() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val ownerId = "remote-input-owner"
        service.onStartCommand(
            Intent(service, PrivilegeAdbPairingService::class.java)
                .setAction(PrivilegeAdbPairingIntentContract.ACTION_START)
                .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_OWNER_ID, ownerId),
            0,
            1,
        )
        val replyIntent = Intent(service, PrivilegeAdbPairingService::class.java)
            .setAction(PrivilegeAdbPairingIntentContract.ACTION_REPLY)
        val remoteInput = RemoteInput.Builder(
            PrivilegeAdbPairingIntentContract.REMOTE_INPUT_PAIRING_CODE,
        ).build()
        RemoteInput.addResultsToIntent(
            arrayOf(remoteInput),
            replyIntent,
            Bundle().apply {
                putCharSequence(
                    PrivilegeAdbPairingIntentContract.REMOTE_INPUT_PAIRING_CODE,
                    "123456",
                )
            },
        )
        service.onStartCommand(replyIntent, 0, 2)

        try {
            PrivilegeAdbPairingService.stop(service, ownerId)

            assertFalse(shadowOf(service).isForegroundStopped)
            assertFalse(shadowOf(service).isStoppedBySelf)

            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

            assertTrue(shadowOf(service).isForegroundStopped)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            PrivilegeAdbPairingService.stop(service, ownerId)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            controller.destroy()
        }
    }
}
