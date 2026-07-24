package priv.kit.ui

import android.Manifest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationUnavailableReason
import priv.kit.ui.adb.pairing.PrivilegeUiAdbPairingActions
import priv.kit.ui.runtime.PrivilegeUiStartGate
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiAdbPairingActionsTest {
    @Test
    fun pendingPermissionFlowBlocksSilentStartUntilUserCancelsIt() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            var permissionRequestCount = 0
            val permissionResult = CompletableDeferred<PrivilegeUiPermissionState?>()
            val start = async(start = CoroutineStart.UNDISPATCHED) {
                actions.startNotificationPairing {
                    permissionRequestCount += 1
                    permissionResult.await()
                }
            }
            assertEquals(1, permissionRequestCount)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            permissionResult.complete(
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )
            start.await()

            assertTrue(store.state.value.pairingNotificationPermissionWarningVisible)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            actions.cancelPendingPairingStart()

            val silentPermit = PrivilegeUiStartGate.tryAcquireSilent()
            assertNotNull(silentPermit)
            silentPermit!!.close()
        }
    }

    @Test
    fun pendingPermissionKeepsPairingStopped() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            var permissionRequestCount = 0
            val permissionResult = CompletableDeferred<PrivilegeUiPermissionState?>()

            val firstStart = async(start = CoroutineStart.UNDISPATCHED) {
                actions.startNotificationPairing {
                    permissionRequestCount += 1
                    permissionResult.await()
                }
            }
            assertEquals(1, permissionRequestCount)
            assertPairingNotStarted(store)
            permissionResult.complete(null)
            firstStart.await()
        }
    }

    @Test
    fun permanentlyDeniedNotificationPermissionShowsWarningWithoutStartingPairingSession() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            var permissionRequestCount = 0
            val permissionResult = CompletableDeferred<PrivilegeUiPermissionState?>()

            val start = async(start = CoroutineStart.UNDISPATCHED) {
                actions.startNotificationPairing {
                    permissionRequestCount += 1
                    permissionResult.await()
                }
            }

            assertEquals(1, permissionRequestCount)
            assertPairingNotStarted(store)

            permissionResult.complete(
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )
            start.await()

            assertTrue(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun rejectedPermissionDispatchDoesNotLeavePairingRequestStuck() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            actions.startNotificationPairing { null }

            assertPairingNotStarted(store)

            var permissionRequestCount = 0
            actions.startNotificationPairing {
                permissionRequestCount += 1
                null
            }
            assertEquals(1, permissionRequestCount)
        }
    }

    @Test
    fun grantedNotificationPermissionStartsPairingSession() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            actions.startNotificationPairing { PrivilegeUiPermissionState.Granted }

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
        }
    }

    @Test
    fun continuingAfterPermanentDenialStartsPairingWithoutNotification() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            showPermanentDenialWarning(actions)

            actions.continuePairingWithoutNotification()

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
            assertTrue(store.state.value.startupLogLines.isNotEmpty())
        }
    }

    @Test
    fun cancellingPendingPermissionKeepsPairingStoppedWhenAStaleResultArrives() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            val permissionResult = CompletableDeferred<PrivilegeUiPermissionState?>()
            val start = async(start = CoroutineStart.UNDISPATCHED) {
                actions.startNotificationPairing { permissionResult.await() }
            }

            actions.cancelPendingPairingStart()
            start.cancelAndJoin()
            permissionResult.complete(PrivilegeUiPermissionState.Granted)

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun permissionGateStopsExistingPairingBeforePermanentDenialCanBeCancelled() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }
            denyNotificationPermission()

            actions.startNotificationPairing {
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied
            }

            assertPairingNotStarted(store)
            actions.cancelPendingPairingStart()

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun ordinaryNotificationDenialStartsPairingWithoutNotification() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            denyNotificationPermission()
            actions.startNotificationPairing {
                PrivilegeUiPermissionState.NotGranted.Denied
            }

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
            assertTrue(store.state.value.startupLogLines.isNotEmpty())
        }
    }

    @Test
    fun stoppingFromNotificationClosesDialogAndPairingSession() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }

            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Stop(store.notificationPairingOwnerId),
            )

            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, store.state.value.pairingStatus)
            assertFalse(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun foregroundFailureEventUsesLocalizedMessageAndKeepsPairingSessionOpen() = runBlocking {
        assertUnavailableNotificationMessage(
            reason = PrivilegeAdbPairingNotificationUnavailableReason.FOREGROUND_SERVICE_FAILED,
            expectedMessage = "无法显示配对通知，请在应用内继续配对",
        )
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun permissionFailureEventUsesLocalizedPermissionMessage() = runBlocking {
        assertUnavailableNotificationMessage(
            reason = PrivilegeAdbPairingNotificationUnavailableReason.NOTIFICATION_PERMISSION_REQUIRED,
            expectedMessage = "需要通知权限",
        )
    }

    private suspend fun assertUnavailableNotificationMessage(
        reason: PrivilegeAdbPairingNotificationUnavailableReason,
        expectedMessage: String,
    ) = coroutineScope {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val actions = PrivilegeUiAdbPairingActions(
            store = store,
            coroutineScope = scope,
            enableTcpMode = {},
        )
        try {
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }
            val snackbar = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(2_000) {
                    store.snackbarTexts.first().asString(store.requireContext())
                }
            }

            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Unavailable(
                    ownerId = store.notificationPairingOwnerId,
                    message = "ForegroundServiceStartNotAllowedException: injected",
                    reason = reason,
                ),
            )

            assertEquals(expectedMessage, snackbar.await())
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }

    @Test
    fun notificationEventFromAnotherOwnerIsIgnored() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }

            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Stop("another-owner"),
            )

            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertTrue(store.state.value.notificationPairingRunning)
        }
    }

    @Test
    fun detachedNotificationWithoutInteractionHostStopsPairingAndReleasesPermit() {
        shadowOf(RuntimeEnvironment.getApplication())
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        withPairingActions(hasInteractionHost = { false }) { store, actions ->
            actions.startNotificationPairing(requestNotificationPermission = { null })
            store.updateState { it.copy(notificationPairingRunning = true) }
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            actions.cancelPairingWithoutInteractionHost(notificationLost = false)
            assertNull(PrivilegeUiStartGate.tryAcquireSilent())

            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Detached(store.notificationPairingOwnerId),
            )

            assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, store.state.value.pairingStatus)
            assertFalse(store.state.value.pairingDialogVisible)
            PrivilegeUiStartGate.tryAcquireSilent()!!.close()
        }
    }

    @Test
    fun stalePairingSubmitDoesNotAcquireInteractivePermit() {
        withPairingActions(hasInteractionHost = { true }) { store, actions ->
            actions.submitNotificationPairingCode()
            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Submit(
                    ownerId = store.notificationPairingOwnerId,
                    pairingCode = "123456",
                ),
            )

            PrivilegeUiStartGate.tryAcquireSilent()!!.close()
        }
    }

    private suspend fun showPermanentDenialWarning(actions: PrivilegeUiAdbPairingActions) {
        denyNotificationPermission()
        actions.startNotificationPairing {
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied
        }
    }

    private fun assertPairingNotStarted(store: PrivilegeUiViewModelStore) {
        assertEquals(PrivilegeUiAdbPairingStatus.NOT_PAIRED, store.state.value.pairingStatus)
        assertFalse(store.state.value.pairingDialogVisible)
        assertFalse(store.state.value.notificationPairingRunning)
        assertTrue(store.state.value.startupLogLines.isEmpty())
    }

    private fun denyNotificationPermission() {
        shadowOf(RuntimeEnvironment.getApplication())
            .denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun withPairingActions(
        hasInteractionHost: () -> Boolean,
        block: suspend CoroutineScope.(PrivilegeUiViewModelStore, PrivilegeUiAdbPairingActions) -> Unit,
    ) = runBlocking {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.cancel()
        val actions = PrivilegeUiAdbPairingActions(
            store = store,
            coroutineScope = scope,
            enableTcpMode = {},
            hasInteractionHost = hasInteractionHost,
        )
        try {
            block(store, actions)
        } finally {
            actions.close()
            scope.cancel()
            store.close()
        }
    }
}
