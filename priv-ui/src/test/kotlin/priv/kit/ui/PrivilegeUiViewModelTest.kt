package priv.kit.ui

import priv.kit.ui.adb.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import priv.kit.core.PrivilegeServerInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @After
    fun noInteractiveGateLeaseLeaksBetweenViewModels() {
        assertNull(PrivilegeUiStartGate.state.value.owner)
    }

    @Test
    fun silentOwnerBlocksInteractiveEntryUntilRuntimeIsReconciled() = runBlocking {
        var silentPermit: AutoCloseable? = PrivilegeUiStartGate.tryAcquireSilent()!!
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        try {
            assertFalse(viewModel.uiInteractionsEnabled)
            viewModel.startInteractive()
            assertFalse(viewModel.state.value.busy)
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, viewModel.state.value.runtimeStartPhase)

            silentPermit?.close()
            silentPermit = null
            withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                while (!viewModel.uiEffectsEnabled.value) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(1)
                }
            }

            assertTrue(viewModel.uiInteractionsEnabled)
        } finally {
            silentPermit?.close()
        }
    }

    @Test
    fun fastSilentCompletionIsEventuallyReconciled() = runBlocking {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        assertTrue(viewModel.uiInteractionsEnabled)

        val previousCompletionSerial =
            PrivilegeUiStartGate.state.value.silentCompletionSerial
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        silentPermit.close()
        assertTrue(
            PrivilegeUiStartGate.state.value.silentCompletionSerial > previousCompletionSerial,
        )

        withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            while (
                !viewModel.uiInteractionsEnabled ||
                !viewModel.state.value.runtimeStatusLoaded
            ) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }
        assertTrue(viewModel.uiInteractionsEnabled)
    }

    @Test
    fun initialRuntimeReconciliationSuppressesAutoRecoveryWarning() {
        val state = PrivilegeUiState(
            desiredEnabled = true,
            runtimeStatus = PrivilegeUiRuntimeStatus.DISCONNECTED,
            runtimeStartPhase = PrivilegeUiRuntimeStartPhase.IDLE,
        )

        assertFalse(
            privilegeUiAutoRecoveryWarningVisible(
                state = state,
                interactionEnabled = true,
            ),
        )
        assertTrue(
            privilegeUiAutoRecoveryWarningVisible(
                state = state.copy(runtimeStatusLoaded = true),
                interactionEnabled = true,
            ),
        )
        assertFalse(
            privilegeUiAutoRecoveryWarningVisible(
                state = state.copy(runtimeStatusLoaded = true),
                interactionEnabled = false,
            ),
        )
    }

    @Test
    fun startupModePanelsWaitForTheirOwnInitialStatus() {
        val state = PrivilegeUiState(
            startupModes = PrivilegeUiStartupMode.entries,
            selectedStartupMode = PrivilegeUiStartupMode.ROOT,
        )

        assertTrue(state.selectedStartupModeStatusLoaded())
        assertFalse(
            state.copy(selectedStartupMode = PrivilegeUiStartupMode.MANUAL_SHELL)
                .selectedStartupModeStatusLoaded(),
        )
        assertFalse(
            state.copy(selectedStartupMode = PrivilegeUiStartupMode.ADB)
                .selectedStartupModeStatusLoaded(),
        )
        assertFalse(
            state.copy(selectedStartupMode = PrivilegeUiStartupMode.EXTERNAL)
                .selectedStartupModeStatusLoaded(),
        )
        assertTrue(
            state.copy(
                selectedStartupMode = PrivilegeUiStartupMode.MANUAL_SHELL,
                manualShellStatusLoaded = true,
            ).selectedStartupModeStatusLoaded(),
        )
        assertTrue(
            state.copy(
                selectedStartupMode = PrivilegeUiStartupMode.ADB,
                adbStatusLoaded = true,
            ).selectedStartupModeStatusLoaded(),
        )
        assertTrue(
            state.copy(
                selectedStartupMode = PrivilegeUiStartupMode.EXTERNAL,
                externalStartStatusLoaded = true,
            ).selectedStartupModeStatusLoaded(),
        )
    }

    @Test
    fun clearStartupLogRemovesVisibleLogContent() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        viewModel.storeForTest().appendStartupLog("diagnostic")

        viewModel.clearStartupLog()

        assertTrue(viewModel.state.value.startupLogLines.isEmpty())
    }

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
                    progressText = PrivilegeUiText.Literal("external"),
                    startedText = PrivilegeUiText.Literal("external requested"),
                    startupSource = null,
                    runtimeStartSource = PrivilegeUiRuntimeStartSource.EXTERNAL,
                ) {
                    addCloseable(
                        AutoCloseable { release.await(30, TimeUnit.SECONDS) },
                    )
                    startCount.incrementAndGet()
                    started.countDown()
                    awaitCancellation()
                },
            )

            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.RUNNING, viewModel.state.value.runtimeStartPhase)

            viewModel.stopCurrentStart()

            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            viewModel.startInteractive()
            viewModel.stopCurrentStart()

            assertEquals(1, startCount.get())
            assertEquals(PrivilegeUiRuntimeStartPhase.CANCELLING, viewModel.state.value.runtimeStartPhase)

            release.countDown()
            assertTrue(
                withTimeoutOrNull(TimeUnit.SECONDS.toMillis(2)) {
                    while (
                        viewModel.state.value.runtimeStartPhase != PrivilegeUiRuntimeStartPhase.IDLE ||
                        viewModel.state.value.busy
                    ) {
                        shadowOf(Looper.getMainLooper()).idle()
                        delay(10L)
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
    fun hostResumeContinuesPendingPairingWithNotificationAfterPermissionIsGranted() = runBlocking {
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
        withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            while (PrivilegeUiStartGate.state.value.owner != null) delay(10L)
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
        val hostId = "permission-host"
        viewModel.registerPermissionHost(hostId)
        var permissionRequest: PrivilegeUiPermissionRequest? = null
        try {
            viewModel.startNotificationPairing()

            permissionRequest = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                viewModel.permissionRequests.first()
            }
            assertTrue(permissionRequest is PrivilegeUiPermissionRequest.Notification)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertFalse(viewModel.state.value.pairingDialogVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)

            viewModel.completeUnlaunchedNotificationPermissionRequest(
                hostId,
                checkNotNull(permissionRequest) as PrivilegeUiPermissionRequest.Notification,
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )
            assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            assertFalse(viewModel.state.value.pairingDialogVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)

            viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)
            assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            PrivilegeUiStartGate.tryAcquireSilent()!!.close()
        } finally {
            permissionRequest?.close()
            viewModel.cancelPendingPairingStart()
            viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)
        }
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun configurationDetachPreservesCompletedPermissionWarning() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val hostId = "permission-host"
        viewModel.registerPermissionHost(hostId)
        viewModel.startNotificationPairing()
        val request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            viewModel.permissionRequests.first()
        } as PrivilegeUiPermissionRequest.Notification
        viewModel.completeUnlaunchedNotificationPermissionRequest(
            hostId,
            request,
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
        )
        assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())

        viewModel.unregisterPermissionHost(hostId, changingConfigurations = true)
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)

        assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        viewModel.registerPermissionHost(hostId)
        viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)
        assertFalse(viewModel.state.value.pairingNotificationPermissionWarningVisible)
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun launchedPermissionRequestSurvivesCollectorRecreationUntilResult() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val hostId = "permission-host"
        viewModel.registerPermissionHost(hostId)
        var request: PrivilegeUiPermissionRequest? = null
        try {
            viewModel.startNotificationPairing()
            request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                viewModel.permissionRequests.first()
            }
            assertTrue(request is PrivilegeUiPermissionRequest.Notification)
            assertTrue(checkNotNull(request).tryMarkLaunched(hostId))

            viewModel.unregisterPermissionHost(hostId, changingConfigurations = true)
            viewModel.registerPermissionHost(hostId)

            val reboundRequest = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                viewModel.permissionRequests.first()
            }

            assertSame(request, reboundRequest)
            assertTrue(reboundRequest.wasLaunched)
            assertFalse(reboundRequest.tryMarkLaunched("other-host"))
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            viewModel.completeNotificationPermissionRequest(
                hostId,
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )
            assertTrue(viewModel.state.value.pairingNotificationPermissionWarningVisible)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        } finally {
            request?.close()
            viewModel.cancelPendingPairingStart()
            viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)
        }
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun configurationDetachedHostDoesNotAcceptNewPermissionRequest() {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val hostId = "permission-host"
        viewModel.registerPermissionHost(hostId)
        viewModel.unregisterPermissionHost(hostId, changingConfigurations = true)

        viewModel.startNotificationPairing()

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        silentPermit!!.close()
        viewModel.registerPermissionHost(hostId)
        viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)
    }

    @Test
    fun onlyOwningPermissionHostUnbindCancelsLaunchedRequest() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val ownerHostId = "owner-host"
        val observerHostId = "observer-host"
        viewModel.registerPermissionHost(ownerHostId)
        viewModel.registerPermissionHost(observerHostId)

        viewModel.startNotificationPairing()
        val request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            viewModel.permissionRequests.first()
        }
        assertTrue(request.tryMarkLaunched(ownerHostId))
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())

        viewModel.unregisterPermissionHost(observerHostId, changingConfigurations = false)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())

        viewModel.unregisterPermissionHost(ownerHostId, changingConfigurations = false)

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        silentPermit!!.close()
    }

    @Test
    fun permissionRequestIsRejectedAfterLastHostUnbinds() {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val hostId = "permission-host"
        viewModel.registerPermissionHost(hostId)
        viewModel.unregisterPermissionHost(hostId, changingConfigurations = false)

        viewModel.startNotificationPairing()

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        silentPermit!!.close()
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

        override suspend fun start(
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
        val runtimeContext = Class.forName("priv.kit.core.internal.runtime.PrivilegeContext")
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
}
