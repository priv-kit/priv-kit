package priv.kit.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.widget.TextView
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingIntentContract as Contract

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 36], qualifiers = "en")
class PrivilegeAdbPairingLocaleTest {
    @Test
    fun switchingLocaleRefreshesNotificationsAndPreservesPairingState() {
        withPairingService(Locale.ENGLISH) { service ->
            service.action(Contract.ACTION_INPUT_RIGHT)
            service.action(Contract.ACTION_INPUT_UP)
            val manager = service.getSystemService(NotificationManager::class.java)

            fun assertNotifications(chinese: Boolean) {
                val status = shadowOf(service).lastForegroundNotification
                assertEquals(if (chinese) "正在配对" else "Pairing",
                    status.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals(if (chinese) "正在搜索配对服务..." else "Searching for the pairing service...",
                    status.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                assertEquals(if (chinese) "停止" else "Stop", status.actions[0].title.toString())
                assertEquals(if (chinese) "输入配对码" else "Enter Pairing Code",
                    status.actions[1].title.toString())
                assertEquals(if (chinese) "配对码" else "Pairing code",
                    status.actions[1].remoteInputs.single().label.toString())
                assertEquals(if (chinese) "无线调试配对" else "Wireless debugging pairing",
                    manager.getNotificationChannel(Contract.NOTIFICATION_CHANNEL_ID).name.toString())
                val input = shadowOf(manager).getNotification(Contract.NOTIFICATION_ID + 1)
                val view = input.contentView.apply(service, null)
                assertEquals("0 [1] 0 0 0 0",
                    view.findViewById<TextView>(R.id.priv_ui_pairing_code_text).text.toString())
                assertEquals(if (chinese) "停止配对" else "Stop pairing",
                    view.findViewById<TextView>(R.id.priv_ui_pairing_close).contentDescription.toString())
                assertTrue(PrivilegeAdbPairingService.isRunning(OWNER_ID))
                assertFalse(shadowOf(service).isStoppedBySelf)
            }

            assertNotifications(chinese = false)
            PrivilegeUi.setLocale(Locale.SIMPLIFIED_CHINESE)
            assertNotifications(chinese = true)
            PrivilegeUi.setLocale(null)
            assertNotifications(chinese = false)

            PrivilegeUi.setLocale(Locale.SIMPLIFIED_CHINESE)
            service.action(Contract.ACTION_INPUT_SUBMIT)
            assertEquals("正在连接无线调试...", shadowOf(service).lastForegroundNotification
                .extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            PrivilegeUi.setLocale(Locale.ENGLISH)
            val working = shadowOf(service).lastForegroundNotification
            assertEquals("Connecting to Wireless debugging...",
                working.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue(working.actions.none { !it.remoteInputs.isNullOrEmpty() })
            assertTrue(PrivilegeAdbPairingService.isRunning(OWNER_ID))
        }
    }

    @Test
    fun localeSetBeforeServiceStartDoesNotOverrideHostTextOrTranslateLiteralStatus() {
        withPairingService(Locale.SIMPLIFIED_CHINESE) { service ->
            assertEquals("正在配对", shadowOf(service).lastForegroundNotification
                .extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            // The same resolver is used by PrivilegeScaffold with the host's LocalContext.
            assertEquals("Pairing", privilegeUiText(R.string.priv_ui_pairing_working_title)
                .asString(service))
            PrivilegeAdbPairingService.updateStatus(OWNER_ID, PrivilegeUiText.Literal("External status"))
            PrivilegeUi.setLocale(Locale.ENGLISH)
            assertEquals("External status", shadowOf(service).lastForegroundNotification
                .extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        }
    }

    private fun withPairingService(locale: Locale, test: (PrivilegeAdbPairingService) -> Unit) {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PrivilegeUi.setLocale(locale)
        val controller = Robolectric.buildService(PrivilegeAdbPairingService::class.java).create()
        val service = controller.get()
        try {
            service.onStartCommand(
                Intent(service, PrivilegeAdbPairingService::class.java)
                    .setAction(Contract.ACTION_START)
                    .putExtra(Contract.EXTRA_NOTIFICATION_OWNER_ID, OWNER_ID)
                    .putExtra(Contract.EXTRA_NOTIFICATION_CHANNEL_ID, Contract.NOTIFICATION_CHANNEL_ID)
                    .putExtra(Contract.EXTRA_NOTIFICATION_ID, Contract.NOTIFICATION_ID),
                0,
                1,
            )
            test(service)
        } finally {
            controller.destroy()
            PrivilegeUi.setLocale(null)
        }
    }

    private fun PrivilegeAdbPairingService.action(action: String) {
        onStartCommand(Intent(this, PrivilegeAdbPairingService::class.java).setAction(action), 0, 2)
    }

    private companion object {
        const val OWNER_ID = "locale-owner"
    }
}
