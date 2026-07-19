package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.adb.pairing.*
import priv.kit.ui.runtime.*
import priv.kit.ui.external.*
import priv.kit.ui.state.*

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.PrivilegeServerInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @Test
    fun defaultConfigOrdersTabsButSelectsAdb() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )

        val state = viewModel.state.value

        assertEquals(
            listOf(
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
            ),
            state.startupModes,
        )
        assertEquals(PrivilegeUiStartupMode.ADB, state.selectedStartupMode)
    }

    @Test
    fun externalModeIsLastAndAdbRemainsSelected() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(TestExternalStartProvider),
            ),
        )

        val state = viewModel.state.value

        assertEquals(
            listOf(
                PrivilegeUiStartupMode.ROOT,
                PrivilegeUiStartupMode.ADB,
                PrivilegeUiStartupMode.MANUAL_SHELL,
                PrivilegeUiStartupMode.EXTERNAL,
            ),
            state.startupModes,
        )
        assertEquals(PrivilegeUiStartupMode.ADB, state.selectedStartupMode)
    }

    @Test
    fun constructorConfigInitializesState() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        val state = viewModel.state.value

        assertEquals(listOf(PrivilegeUiStartupMode.ROOT), state.startupModes)
        assertEquals(PrivilegeUiStartupMode.ROOT, state.selectedStartupMode)
    }

    @Test
    @Config(sdk = [29])
    fun android10DoesNotStartWirelessAdbStatusPolling() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ADB),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )

        val handle = viewModel.startWirelessAdbStatusPolling()
        assertSame(PrivilegeUiNoopCloseable, handle)

        viewModel.onHostResume()
        assertEquals(
            PrivilegeUiWirelessAdbStatus.UNKNOWN,
            viewModel.state.value.wirelessDebuggingStatus,
        )
    }

    @Test
    fun directAdbStartIncludesWirelessWithoutCachedReadiness() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        store.updateState {
            it.copy(
                wifiConnected = false,
                wirelessDebuggingStatus = PrivilegeUiWirelessAdbStatus.OFF,
                wirelessPairingCheckStatus = PrivilegeUiWirelessAdbStatus.UNKNOWN,
                managedWirelessAdbStatus = PrivilegeUiManagedWirelessAdbStatus.UNKNOWN,
            )
        }

        assertEquals(
            PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            viewModel.adbActionsForTest().directStartAttempts().single().runtimeStartSource,
        )
    }

    @Test
    fun directAdbStartUsesConfiguredTcpWithoutCachedAuthorization() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        val adbActions = viewModel.adbActionsForTest()
        store.config = store.config.copy(adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING)
        store.updateState {
            it.copy(
                wifiConnected = false,
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
            )
        }

        val attempts = adbActions.directStartAttempts()

        assertTrue(attempts.first() is PrivilegeUiRuntimeStartAttempt.Workflow)
        assertEquals(
            listOf(
                PrivilegeUiRuntimeStartSource.ADB_STATIC_TCP,
                PrivilegeUiRuntimeStartSource.ADB_WIRELESS,
            ),
            attempts.map { it.runtimeStartSource },
        )
    }

    @Test
    fun publicStartAndCancelCommandsAreIgnoredWhileCancelling() = runBlocking {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = setOf(PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                startTimeoutMillis = 250L,
            ),
        )
        val actions = viewModel.runtimeActionsForTest()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val startCount = AtomicInteger(0)
        try {
            actions.runServerStartRequest(
                PrivilegeUiRuntimeStartAttempt.Request(
                    message = "external",
                    startedMessage = "external requested",
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    startCount.incrementAndGet()
                    started.countDown()
                    release.await(30, TimeUnit.SECONDS)
                },
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, viewModel.state.value.runtimeStartPhase)

            viewModel.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            viewModel.startAvailable()
            viewModel.stopCurrentStart()

            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            release.countDown()
            assertTrue(
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
                    viewModel.state.first { state ->
                        state.runtimeStartPhase == PrivilegeUiRuntimeStartPhase.IDLE && !state.busy
                    }
                    true
                } == true,
            )
        } finally {
            release.countDown()
            actions.close()
        }
    }

    @Test
    fun hostResumeKeepsPendingTcpAuthorizationRequestAlive() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        val store = viewModel.storeForTest()
        val request = CloseCounter()
        store.tcpAuthorizationRequest = request
        store.updateState {
            it.copy(
                tcpAuthorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            )
        }

        viewModel.onHostResume()

        assertEquals(0, request.closeCount)
        assertEquals(request, store.tcpAuthorizationRequest)
        assertEquals(
            PrivilegeUiAdbTcpAuthorizationStatus.AUTHORIZING,
            viewModel.state.value.tcpAuthorizationStatus,
        )
    }

    @Test
    fun hostResumeRefreshesBatteryOptimizationPromptVisibility() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        assertTrue(viewModel.batteryOptimizationPromptVisible.value)

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        viewModel.dispatchHostResume()

        assertFalse(viewModel.batteryOptimizationPromptVisible.value)

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        viewModel.dispatchHostResume()

        assertTrue(viewModel.batteryOptimizationPromptVisible.value)
    }

    @Test
    fun foregroundDispatchRefreshesBatteryOptimizationBeforeCallingHostOverride() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        val viewModel = HostResumePrivilegeUiViewModel(application)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)

        viewModel.dispatchHostResume()

        assertTrue(viewModel.batteryOptimizationPromptVisible.value)
        assertEquals(1, viewModel.hostResumeCount)
        assertEquals(true, viewModel.promptVisibleDuringHostResume)
    }

    @Test
    fun windowFocusDispatchRefreshesWithoutCallingHostResumeOverride() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        val viewModel = HostResumePrivilegeUiViewModel(application)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)

        viewModel.dispatchHostWindowFocus()

        assertTrue(viewModel.batteryOptimizationPromptVisible.value)
        assertEquals(0, viewModel.hostResumeCount)
    }

    @Test
    fun hostRefreshRechecksBatteryOptimizationAfterOemAsyncUpdate() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        viewModel.dispatchHostWindowFocus()
        assertTrue(viewModel.batteryOptimizationPromptVisible.value)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)

        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertFalse(viewModel.batteryOptimizationPromptVisible.value)
    }

    @Test
    fun hostRefreshRechecksBatteryOptimizationAfterOemAsyncRevocation() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        viewModel.dispatchHostWindowFocus()
        assertFalse(viewModel.batteryOptimizationPromptVisible.value)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)

        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertTrue(viewModel.batteryOptimizationPromptVisible.value)
    }

    @Test
    fun hostEventsAreOverridableAndConnectionsAreDeliveredOncePerSerial() {
        val viewModel = HostEventPrivilegeUiViewModel(application())
        val first = PrivilegeServerInfo(uid = 2000, pid = 10, protocolVersion = 1)
        val second = PrivilegeServerInfo(uid = 0, pid = 11, protocolVersion = 1)

        assertTrue(viewModel.dispatchBackClick())
        viewModel.dispatchConnected(connectionSerial = 1L, serverInfo = first)
        viewModel.dispatchConnected(connectionSerial = 1L, serverInfo = second)
        viewModel.dispatchConnected(connectionSerial = 2L, serverInfo = second)

        assertEquals(1, viewModel.backClickCount)
        assertEquals(listOf(first, second), viewModel.connectedServers)
    }

    @Test
    fun baseBackEventUsesSystemBack() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        assertFalse(viewModel.dispatchBackClick())
    }

    @Test
    fun notificationPermissionSettingsRequestKeepsWarningAndOpensCurrentAppSettings() {
        val application = application()
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val store = viewModel.storeForTest()
        store.updateState {
            it.copy(pairingNotificationPermissionWarningVisible = true)
        }
        val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
        val startedLogCountBefore = viewModel.state.value.startupLogLines.count {
            it == startedMessage
        }

        viewModel.dispatchNotificationPermissionSettingsRequest(application)

        val intent = shadowOf(application).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(
            application.packageName,
            intent.getStringExtra(Settings.EXTRA_APP_PACKAGE),
        )
        assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        assertFalse(viewModel.state.value.pairingDialogVisible)
        assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
        assertFalse(viewModel.state.value.notificationPairingRunning)
        assertEquals(
            startedLogCountBefore,
            viewModel.state.value.startupLogLines.count { it == startedMessage },
        )
    }

    @Test
    fun notificationPermissionSettingsRequestIsOverridable() {
        val application = application()
        val viewModel = NotificationSettingsPrivilegeUiViewModel(application)
        viewModel.storeForTest().updateState {
            it.copy(pairingNotificationPermissionWarningVisible = true)
        }

        viewModel.dispatchNotificationPermissionSettingsRequest(application)

        assertSame(application, viewModel.openedContext)
        assertNull(shadowOf(application).nextStartedActivity)
        assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
    }

    @Test
    fun hostResumeContinuesPendingPairingWithNotificationAfterPermissionIsGranted() {
        val application = application()
        val shadowApplication = shadowOf(application)
        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.storeForTest().updateState {
            it.copy(pairingNotificationPermissionWarningVisible = true)
        }
        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        try {
            viewModel.dispatchHostResume()

            val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
            assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(
                PrivilegeUiAdbPairingStatus.SEARCHING,
                viewModel.state.value.pairingStatus,
            )
            assertTrue(viewModel.state.value.pairingDialogVisible)
            assertTrue(viewModel.state.value.notificationPairingRunning)
            assertEquals(1, viewModel.state.value.startupLogLines.count { it == startedMessage })

            viewModel.dispatchHostWindowFocus()

            assertEquals(1, viewModel.state.value.startupLogLines.count { it == startedMessage })
        } finally {
            viewModel.stopNotificationPairing()
        }
    }

    @Test
    fun hostResumeKeepsWarningWhenNotificationPermissionRemainsUnavailable() {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.storeForTest().updateState {
            it.copy(pairingNotificationPermissionWarningVisible = true)
        }
        val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
        val startedLogCountBefore = viewModel.state.value.startupLogLines.count {
            it == startedMessage
        }

        viewModel.dispatchHostResume()

        assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        assertFalse(viewModel.state.value.pairingDialogVisible)
        assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
        assertFalse(viewModel.state.value.notificationPairingRunning)
        assertEquals(
            startedLogCountBefore,
            viewModel.state.value.startupLogLines.count { it == startedMessage },
        )
    }

    @Test
    fun hostResumeDoesNotStartPairingWithoutPendingWarning() {
        val application = application()
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
        val startedLogCountBefore = viewModel.state.value.startupLogLines.count {
            it == startedMessage
        }

        viewModel.dispatchHostResume()

        assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        assertFalse(viewModel.state.value.pairingDialogVisible)
        assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
        assertFalse(viewModel.state.value.notificationPairingRunning)
        assertEquals(
            startedLogCountBefore,
            viewModel.state.value.startupLogLines.count { it == startedMessage },
        )
    }

    @Test
    fun permanentlyDeniedNotificationPermissionShowsWarningBeforePairingSessionStarts() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        try {
            viewModel.startNotificationPairing()

            assertEquals(
                PrivilegeUiPermissionRequest.Notification,
                withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                    viewModel.permissionRequests.first()
                },
            )
            assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertFalse(viewModel.state.value.pairingDialogVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)

            viewModel.handleNotificationPermissionResult(
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )

            assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertFalse(viewModel.state.value.pairingDialogVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)
        } finally {
            viewModel.cancelPendingPairingStart()
        }
    }

    private class RootOnlyPrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = setOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    )

    private class ConfiguredPrivilegeUiViewModel(
        application: Application,
        config: PrivilegeUiConfig,
    ) : PrivilegeUiViewModel(
        application = application,
        config = config,
    )

    private class HostEventPrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = setOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    ) {
        var backClickCount = 0
            private set
        val connectedServers = mutableListOf<PrivilegeServerInfo>()

        override fun onBackClick(): Boolean {
            backClickCount += 1
            return true
        }

        override fun onConnected(serverInfo: PrivilegeServerInfo) {
            connectedServers += serverInfo
        }
    }

    private class HostResumePrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = setOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    ) {
        var hostResumeCount = 0
            private set
        var promptVisibleDuringHostResume: Boolean? = null
            private set

        override fun onHostResume() {
            hostResumeCount += 1
            promptVisibleDuringHostResume = batteryOptimizationPromptVisible.value
        }
    }

    private class NotificationSettingsPrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = setOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    ) {
        var openedContext: Context? = null
            private set

        override fun onNotificationPermissionSettingsRequested(context: Context) {
            openedContext = context
        }
    }

    private object TestExternalStartProvider : PrivilegeUiExternalStartProvider {
        override val id: String = "test"
        override val label: CharSequence = "Test"

        override fun start(
            context: Context,
            commandLine: String,
        ) = Unit
    }

    private fun configuredViewModel(config: PrivilegeUiConfig): ConfiguredPrivilegeUiViewModel =
        ConfiguredPrivilegeUiViewModel(
            application = application(),
            config = config,
        )

    private fun application(): Application =
        (RuntimeEnvironment.getApplication() as Application).also(::installRuntimeContext)

    private fun installRuntimeContext(context: Context) {
        val runtimeContext = Class.forName("priv.kit.internal.runtime.PrivilegeContext")
        val instance = runtimeContext.getField("INSTANCE").get(null)
        runtimeContext.getDeclaredMethod("install", Context::class.java).invoke(instance, context)
    }

    private fun PrivilegeUiViewModel.storeForTest(): PrivilegeUiViewModelStore {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("store")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiViewModelStore
    }

    private fun PrivilegeUiViewModel.adbActionsForTest(): PrivilegeUiAdbActions {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("adbActions")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiAdbActions
    }

    private fun PrivilegeUiViewModel.runtimeActionsForTest(): PrivilegeUiRuntimeActions {
        val field = PrivilegeUiViewModel::class.java.getDeclaredField("runtimeActions")
        field.isAccessible = true
        return field.get(this) as PrivilegeUiRuntimeActions
    }

    private class CloseCounter : Closeable {
        var closeCount = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
