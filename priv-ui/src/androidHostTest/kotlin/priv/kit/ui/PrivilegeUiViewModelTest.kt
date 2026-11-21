package priv.kit.ui

import priv.kit.core.PrivilegeExternalStartProvider
import priv.kit.core.PrivilegeExternalStartResult
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.ui.adb.*
import priv.kit.ui.runtime.*
import priv.kit.ui.state.*

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.PowerManager
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiViewModelTest {
    @After
    fun noInteractiveGateLeaseLeaksBetweenViewModels() {
        assertNull(PrivilegeUiStartGate.state.value.owner)
    }

    @Test
    fun silentOwnerBlocksInteractiveEntryUntilSilentStartCompletes() = runBlocking {
        var silentPermit: AutoCloseable? = PrivilegeUiStartGate.tryAcquireSilent()!!
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        try {
            assertFalse(viewModel.uiInteractionsEnabled)
            viewModel.startInteractive()
            assertFalse(viewModel.state.value.busy)
            assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, viewModel.state.value.runtimeStartPhase)

            silentPermit?.close()
            silentPermit = null
            viewModel.awaitInitialLoad()

            assertTrue(viewModel.uiInteractionsEnabled)
        } finally {
            silentPermit?.close()
        }
    }

    @Test
    fun fastSilentCompletionRestoresInteractions() = runBlocking {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        val previousCompletionSerial =
            PrivilegeUiStartGate.state.value.silentCompletionSerial
        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()!!
        silentPermit.close()
        assertTrue(
            PrivilegeUiStartGate.state.value.silentCompletionSerial > previousCompletionSerial,
        )

        viewModel.awaitInitialLoad()
        assertTrue(viewModel.uiInteractionsEnabled)
    }



    @Test
    fun initialLoadDoesNotDisableInteractions() = runBlocking {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        assertTrue(viewModel.uiInteractionsEnabled)

        viewModel.awaitInitialLoad()
        assertTrue(viewModel.uiInteractionsEnabled)
    }

    @Test
    fun slowExternalSnapshotDoesNotDelayPageInitialization() = runBlocking {
        val snapshotStarted = CompletableDeferred<Unit>()
        val releaseSnapshot = CompletableDeferred<Unit>()
        val provider = object : PrivilegeExternalStartProvider {
            override val id: String = "slow"
            override fun getLabel(context: Context): CharSequence = "Slow"

            override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult {
                snapshotStarted.complete(Unit)
                releaseSnapshot.await()
                return PrivilegeExternalStartResult(authorized = true)
            }

            override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
                error("Unexpected authorization request")

            override suspend fun runStarter(
                context: Context,
                commandLine: String,
                startupLogListener: PrivilegeStartupLogListener?,
            ): String = error("Unexpected start")
        }
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = listOf(
                    PrivilegeUiStartupMode.ROOT,
                    PrivilegeUiStartupMode.EXTERNAL,
                ),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(provider),
            ),
        )

        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            snapshotStarted.await()
        }

        assertTrue(viewModel.uiInteractionsEnabled)
        assertFalse(viewModel.state.value.externalStartItems.single().statusLoaded)

        releaseSnapshot.complete(Unit)
        withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            while (!viewModel.state.value.externalStartItems.single().statusLoaded) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }

        assertTrue(viewModel.state.value.externalStartItems.single().canStart)
        viewModel.unregisterPermissionHost(changingConfigurations = false)
    }

    @Test
    fun clearStartupLogRemovesVisibleLogContent() = runBlocking {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        viewModel.awaitInitialLoad()
        viewModel.storeForTest().appendStartupLog("diagnostic")

        viewModel.clearStartupLog()

        assertTrue(viewModel.state.value.startupLogLines.isEmpty())
    }

    @Test
    fun connectedBuiltInStartRequestsConfirmationAndCancelKeepsServerState() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())
        viewModel.storeForTest().updateState {
            it.copy(runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED)
        }

        viewModel.startRoot()

        assertEquals(
            PrivilegeUiServerRestartRequest.Root,
            viewModel.state.value.restartConfirmationTarget,
        )
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, viewModel.state.value.runtimeStatus)
        assertEquals(PrivilegeUiRuntimeStartPhase.IDLE, viewModel.state.value.runtimeStartPhase)

        viewModel.cancelServerRestart()

        assertNull(viewModel.state.value.restartConfirmationTarget)
        assertEquals(PrivilegeUiRuntimeStatus.CONNECTED, viewModel.state.value.runtimeStatus)

        viewModel.startRoot()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            PrivilegeUiServerRestartRequest.Root,
            viewModel.serverRestartConfirmation.value,
        )
    }

    @Test
    fun connectedStartConfirmationRetainsTheRequestedMethod() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = listOf(
                    PrivilegeUiStartupMode.ADB,
                    PrivilegeUiStartupMode.EXTERNAL,
                ),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                externalStartProviders = listOf(TestExternalStartProvider),
            ),
        )
        viewModel.storeForTest().updateState {
            it.copy(runtimeStatus = PrivilegeUiRuntimeStatus.CONNECTED)
        }

        viewModel.startWirelessAdb()
        assertEquals(
            PrivilegeUiServerRestartRequest.WirelessAdb,
            viewModel.state.value.restartConfirmationTarget,
        )
        viewModel.cancelServerRestart()

        viewModel.startStaticTcpAdb()
        assertEquals(
            PrivilegeUiServerRestartRequest.StaticTcpAdb,
            viewModel.state.value.restartConfirmationTarget,
        )
        viewModel.cancelServerRestart()

        viewModel.authorizeOrStartExternal(TestExternalStartProvider.id)
        assertEquals(
            PrivilegeUiServerRestartRequest.External(TestExternalStartProvider.id),
            viewModel.state.value.restartConfirmationTarget,
        )
    }

    @Test
    fun defaultConfigOrdersStartupModes() {
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
    }

    @Test
    fun adbIdentityLoadFinishesBeforeViewModelConstructionReturns() {
        val viewModel = configuredViewModel(
            PrivilegeUiConfig(
                startupModes = listOf(PrivilegeUiStartupMode.ADB),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
            ),
        )

        val state = viewModel.state.value
        assertTrue(
            state.adbKeyFingerprint != null ||
                state.startupLogLines.isNotEmpty(),
        )
    }

    @Test
    fun externalModeIsLast() {
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
    }

    @Test
    fun constructorConfigInitializesState() {
        val viewModel = RootOnlyPrivilegeUiViewModel(application())

        val state = viewModel.state.value

        assertEquals(listOf(PrivilegeUiStartupMode.ROOT), state.startupModes)
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
        val viewModel = ConfiguredPrivilegeUiViewModel(
            application = application(),
            config = PrivilegeUiConfig(adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING),
        )
        val store = viewModel.storeForTest()
        val adbActions = viewModel.adbActionsForTest()
        store.updateState {
            it.copy(
                wifiConnected = false,
                staticTcp = it.staticTcp.copy(
                    authorizationStatus = PrivilegeUiAdbTcpAuthorizationStatus.UNAVAILABLE,
                ),
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
                startupModes = listOf(PrivilegeUiStartupMode.ROOT),
                adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
                startTimeoutMillis = 250L,
            ),
        )
        viewModel.awaitInitialLoad()
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
    fun hostResumeAndPermissionResultRefreshAdbPermissionCard() = runBlocking {
        val application = application()
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        val originalSdk = android.os.Build.VERSION.SDK_INT
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        try {
            // Robolectric's Android 16 runtime supplies permission state; only the
            // library's Android 17 policy check is exercised with the higher SDK.
            org.robolectric.util.ReflectionHelpers.setStaticField(
                android.os.Build.VERSION::class.java, "SDK_INT", 37,
            )
            shadowOf(application).denyPermissions(permission)
            viewModel.dispatchHostResume()
            assertTrue(viewModel.state.value.localNetworkPermissionMissing)

            viewModel.registerPermissionHost()
            viewModel.requestAdbLocalNetworkPermission()
            (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission).denyForTest()
            viewModel.unregisterPermissionHost(changingConfigurations = false)

            shadowOf(application).grantPermissions(permission)
            viewModel.dispatchHostResume()
            assertFalse(viewModel.state.value.localNetworkPermissionMissing)

            shadowOf(application).denyPermissions(permission)
            viewModel.dispatchHostWindowFocus()
            assertTrue(viewModel.state.value.localNetworkPermissionMissing)
        } finally {
            org.robolectric.util.ReflectionHelpers.setStaticField(
                android.os.Build.VERSION::class.java, "SDK_INT", originalSdk,
            )
        }
    }

    @Test
    fun hostResumeRefreshesBatteryOptimizationPromptVisibility() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        assertTrue(viewModel.batteryOptimizationRestricted.value)

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        viewModel.dispatchHostResume()

        assertFalse(viewModel.batteryOptimizationRestricted.value)

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        viewModel.dispatchHostResume()

        assertTrue(viewModel.batteryOptimizationRestricted.value)
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

        assertTrue(viewModel.batteryOptimizationRestricted.value)
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

        assertTrue(viewModel.batteryOptimizationRestricted.value)
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
        assertTrue(viewModel.batteryOptimizationRestricted.value)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)

        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertFalse(viewModel.batteryOptimizationRestricted.value)
    }

    @Test
    fun hostRefreshRechecksBatteryOptimizationAfterOemAsyncRevocation() {
        val application = application()
        val powerManager = application.getSystemService(PowerManager::class.java)
        val shadowPowerManager = shadowOf(powerManager)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)

        viewModel.dispatchHostWindowFocus()
        assertFalse(viewModel.batteryOptimizationRestricted.value)
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)

        shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.SECONDS)

        assertTrue(viewModel.batteryOptimizationRestricted.value)
    }

    @Test
    fun screenActionsRunVmOwnedBatteryHandlerWithoutChangingPairingDecision() = runBlocking {
        var requests = 0
        val viewModel = object : PrivilegeUiViewModel(application(), PrivilegeUiConfig(
            startupModes = listOf(PrivilegeUiStartupMode.ROOT),
        )) {
            override val accessHandler = PrivilegeUiAccessHandler {
                assertEquals(PrivilegeUiAccessRequest.BatteryOptimization, it)
                requests++
                false
            }
        }
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        viewModel.storeForTest().updateState {
            it.copy(pairingInteraction = PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        }
        viewModel.screenActions().requestBatteryOptimization()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, requests)
        assertEquals(PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION, viewModel.state.value.pairingInteraction)
        assertNull(shadowOf(application()).nextStartedActivity)
        viewModel.unregisterPermissionHost(changingConfigurations = false)
    }

    @Test
    fun hostResumeContinuesPendingPairingWithNotificationAfterPermissionIsGranted() = runBlocking {
        val application = application()
        val shadowApplication = shadowOf(application)
        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        val pendingStart = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.adbActionsForTest().startNotificationPairing {
                false
            }
        }
        assertTrue(pendingStart.isActive)
        assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        try {
            viewModel.dispatchHostResume()
            pendingStart.await()

            val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
            assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
            assertEquals(
                PrivilegeUiAdbPairingStatus.SEARCHING,
                viewModel.state.value.pairingStatus,
            )
            assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.ENTER_CODE)
            assertTrue(viewModel.state.value.notificationPairingRunning)
            assertEquals(1, viewModel.state.value.startupLogLines.count { it == startedMessage })

            viewModel.dispatchHostWindowFocus()

            assertEquals(1, viewModel.state.value.startupLogLines.count { it == startedMessage })
        } finally {
            pendingStart.cancel()
            viewModel.cancelPairing()
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
            it.copy(pairingInteraction = PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        }
        val startedMessage = application.getString(R.string.priv_ui_notification_pairing_started)
        val startedLogCountBefore = viewModel.state.value.startupLogLines.count {
            it == startedMessage
        }

        viewModel.dispatchHostResume()

        assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.ENTER_CODE)
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

        assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.ENTER_CODE)
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
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        var permissionRequest: PrivilegeUiAccessCommands.Command.Permission? = null
        try {
            viewModel.startNotificationPairing()

            permissionRequest = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission)
            }
            assertTrue(permissionRequest.permission == PrivilegeUiAccessRequest.Permission.Notifications.permission)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
            assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.ENTER_CODE)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)

            checkNotNull(permissionRequest).denyForTest()
            assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
            assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.ENTER_CODE)
            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, viewModel.state.value.pairingStatus)
            assertFalse(viewModel.state.value.notificationPairingRunning)

            viewModel.unregisterPermissionHost(changingConfigurations = false)
            assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
            PrivilegeUiStartGate.tryAcquireSilent()!!.close()
        } finally {
            permissionRequest?.result?.cancel()
            viewModel.cancelPendingPairingStart()
            viewModel.unregisterPermissionHost(changingConfigurations = false)
        }
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun configurationDetachPreservesCompletedPermissionWarning() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        viewModel.startNotificationPairing()
        val request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission)
        }
        request.denyForTest()
        assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())

        viewModel.unregisterPermissionHost(changingConfigurations = true)
        shadowOf(Looper.getMainLooper()).idleFor(11, TimeUnit.SECONDS)

        assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        viewModel.registerPermissionHost()
        viewModel.unregisterPermissionHost(changingConfigurations = false)
        assertFalse(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun pendingPermissionRequestSurvivesCollectorRecreationUntilResult() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        var request: PrivilegeUiAccessCommands.Command.Permission? = null
        try {
            viewModel.startNotificationPairing()
            request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission)
            }
            assertTrue(request.permission == PrivilegeUiAccessRequest.Permission.Notifications.permission)

            viewModel.unregisterPermissionHost(changingConfigurations = true)
            viewModel.registerPermissionHost()

            val reboundRequest = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
                (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission)
            }

            assertSame(request, reboundRequest)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            reboundRequest.denyForTest()
            assertTrue(viewModel.state.value.pairingInteraction == PrivilegeUiPairingInteraction.CONFIRM_WITHOUT_NOTIFICATION)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())
        } finally {
            request?.result?.cancel()
            viewModel.cancelPendingPairingStart()
            viewModel.unregisterPermissionHost(changingConfigurations = false)
        }
        PrivilegeUiStartGate.tryAcquireSilent()!!.close()
    }

    @Test
    fun configurationDetachedHostDoesNotAcceptNewPermissionRequest() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        viewModel.unregisterPermissionHost(changingConfigurations = true)

        viewModel.startNotificationPairing()

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        silentPermit!!.close()
        viewModel.registerPermissionHost()
        viewModel.unregisterPermissionHost(changingConfigurations = false)
    }

    @Test
    fun unbindingPageCancelsPendingRequest() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()

        viewModel.startNotificationPairing()
        val request = withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            (viewModel.accessCommands.pending.first { it != null } as PrivilegeUiAccessCommands.Command.Permission)
        }
        assertNull(PrivilegeUiStartGate.tryAcquireSilent())

        viewModel.unregisterPermissionHost(changingConfigurations = false)

        val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
        assertNotNull(silentPermit)
        silentPermit!!.close()
    }

    @Test
    fun permissionRequestIsRejectedAfterLastHostUnbinds() = runBlocking {
        val application = application()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val viewModel = RootOnlyPrivilegeUiViewModel(application)
        viewModel.awaitInitialLoad()
        viewModel.registerPermissionHost()
        viewModel.unregisterPermissionHost(changingConfigurations = false)

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
            startupModes = listOf(PrivilegeUiStartupMode.ROOT),
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



    private class HostResumePrivilegeUiViewModel(
        application: Application,
    ) : PrivilegeUiViewModel(
        application = application,
        config = PrivilegeUiConfig(
            startupModes = listOf(PrivilegeUiStartupMode.ROOT),
            adbTcpPolicy = PrivilegeUiAdbTcpPolicy.DISABLED,
        ),
    ) {
        var hostResumeCount = 0
            private set
        var promptVisibleDuringHostResume: Boolean? = null
            private set

        override fun onHostResume() {
            hostResumeCount += 1
            promptVisibleDuringHostResume = batteryOptimizationRestricted.value
        }
    }



    private object TestExternalStartProvider : PrivilegeExternalStartProvider {
        override val id: String = "test"
        override fun getLabel(context: Context): CharSequence = "Test"

        override suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult =
            PrivilegeExternalStartResult(authorized = null)

        override suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult =
            error("Unexpected authorization request")

        override suspend fun runStarter(
            context: Context,
            commandLine: String,
            startupLogListener: PrivilegeStartupLogListener?,
        ): String = error("Unexpected start")
    }

    private fun configuredViewModel(config: PrivilegeUiConfig): ConfiguredPrivilegeUiViewModel =
        ConfiguredPrivilegeUiViewModel(
            application = application(),
            config = config,
        )

    private suspend fun PrivilegeUiAccessCommands.Command.Permission.denyForTest() {
        result.complete(PrivilegeUiPermissionState.NotGranted.Denied)
        shadowOf(Looper.getMainLooper()).idle()
        kotlinx.coroutines.yield()
    }

    private fun application(): Application =
        (RuntimeEnvironment.getApplication() as Application).also(PrivilegeContext::install)

    private suspend fun PrivilegeUiViewModel.awaitInitialLoad() {
        val effects = PrivilegeUiViewModel::class.java.getDeclaredField("effectsCoordinator")
            .apply { isAccessible = true }.get(this)
        val initialLoadCompleted = PrivilegeUiEffectsCoordinator::class.java
            .getDeclaredField("initialLoadCompleted").apply { isAccessible = true }
        withTimeout(TimeUnit.SECONDS.toMillis(2)) {
            while (!initialLoadCompleted.getBoolean(effects)) {
                shadowOf(Looper.getMainLooper()).idle()
                delay(1)
            }
        }
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
