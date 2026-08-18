package priv.kit.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun unavailableNewOwnerDoesNotStopActiveOwner() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val activeOwnerId = "active-owner"
        val activeChannelId = "active-pairing"
        val activeNotificationId = 450
        service.onStartCommand(
            service.pairingStartIntent(
                ownerId = activeOwnerId,
                channelId = activeChannelId,
                notificationId = activeNotificationId,
            ),
            0,
            1,
        )
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                "blocked-pairing",
                "Blocked pairing",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        try {
            val started = PrivilegeAdbPairingService.startWithText(
                context = service,
                ownerId = "unavailable-owner",
                statusText = PrivilegeUiText.Literal("Unavailable"),
                notificationChannelId = "blocked-pairing",
                notificationId = 550,
            )

            assertFalse(started)
            assertTrue(PrivilegeAdbPairingService.isRunning(activeOwnerId))
            assertNotNull(shadowOf(manager).getNotification(activeNotificationId + 1))
        } finally {
            PrivilegeAdbPairingService.stop(service, activeOwnerId)
            controller.destroy()
        }
    }

    @Test
    fun channelDisabledBeforeStartDeliveryDoesNotReplaceActiveOwner() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val activeOwnerId = "delivery-active-owner"
        val activeChannelId = "delivery-active-pairing"
        val activeNotificationId = 750
        val requestedOwnerId = "delivery-unavailable-owner"
        val requestedChannelId = "delivery-blocked-pairing"
        val requestedNotificationId = 850
        service.onStartCommand(
            service.pairingStartIntent(activeOwnerId, activeChannelId, activeNotificationId),
            0,
            1,
        )
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                requestedChannelId,
                "Requested pairing",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )

        try {
            assertTrue(
                PrivilegeAdbPairingService.startWithText(
                    context = service,
                    ownerId = requestedOwnerId,
                    statusText = PrivilegeUiText.Literal("Requested"),
                    notificationChannelId = requestedChannelId,
                    notificationId = requestedNotificationId,
                ),
            )
            manager.deleteNotificationChannel(requestedChannelId)
            manager.createNotificationChannel(
                NotificationChannel(
                    requestedChannelId,
                    "Requested pairing",
                    NotificationManager.IMPORTANCE_NONE,
                ),
            )

            service.onStartCommand(
                service.pairingStartIntent(
                    requestedOwnerId,
                    requestedChannelId,
                    requestedNotificationId,
                ),
                0,
                2,
            )

            assertTrue(PrivilegeAdbPairingService.isRunning(activeOwnerId))
            assertTrue(PrivilegeAdbPairingService.isRequested(activeOwnerId))
            assertNotNull(shadowOf(manager).getNotification(activeNotificationId + 1))
        } finally {
            PrivilegeAdbPairingService.stop(service, activeOwnerId)
            controller.destroy()
        }
    }

    @Test
    fun coldNotificationActionIsRejected() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()

        try {
            service.onStartCommand(
                Intent(service, PrivilegeAdbPairingService::class.java)
                    .setAction(PrivilegeAdbPairingIntentContract.ACTION_STOP),
                0,
                1,
            )

            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun customNotificationIdsAreUsedByTheService() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val ownerId = "custom-notification-owner"
        val channelId = "host_pairing"
        val notificationId = 450
        service.onStartCommand(
            service.pairingStartIntent(ownerId, channelId, notificationId),
            0,
            1,
        )

        try {
            val manager = service.getSystemService(NotificationManager::class.java)
            assertNotNull(manager.getNotificationChannel(channelId))
            assertEquals(notificationId, shadowOf(service).lastForegroundNotificationId)
            assertEquals(channelId, shadowOf(service).lastForegroundNotification.channelId)
            assertNotNull(shadowOf(manager).getNotification(notificationId + 1))

            PrivilegeAdbPairingService.stop(service, ownerId)

            assertNull(shadowOf(manager).getNotification(notificationId + 1))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun stopWithoutRemoteInputStopsImmediately() {
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        val ownerId = "ordinary-owner"
        service.onStartCommand(
            service.pairingStartIntent(ownerId),
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
            service.pairingStartIntent(ownerId),
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

    private fun PrivilegeAdbPairingService.pairingStartIntent(
        ownerId: String,
        channelId: String = PrivilegeAdbPairingIntentContract.NOTIFICATION_CHANNEL_ID,
        notificationId: Int = PrivilegeAdbPairingIntentContract.NOTIFICATION_ID,
    ): Intent =
        Intent(this, PrivilegeAdbPairingService::class.java)
            .setAction(PrivilegeAdbPairingIntentContract.ACTION_START)
            .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_OWNER_ID, ownerId)
            .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_CHANNEL_ID, channelId)
            .putExtra(PrivilegeAdbPairingIntentContract.EXTRA_NOTIFICATION_ID, notificationId)
}
