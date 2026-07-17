package priv.kit.ui

import android.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent
import priv.kit.ui.adb.pairing.PrivilegeUiAdbPairingActions
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiAdbPairingActionsTest {
    @Test
    fun repeatedStartWhilePermissionIsPendingRequestsPermissionOnce() {
        withPairingActions { store, actions ->
            denyNotificationPermission()
            var permissionRequestCount = 0

            actions.startNotificationPairing { permissionRequestCount += 1 }
            actions.startNotificationPairing { permissionRequestCount += 1 }

            assertEquals(1, permissionRequestCount)
            assertTrue(store.startNotificationPairingAfterPermission)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun permanentlyDeniedNotificationPermissionShowsWarningWithoutStartingPairingSession() {
        withPairingActions { store, actions ->
            denyNotificationPermission()
            var permissionRequestCount = 0

            actions.startNotificationPairing {
                permissionRequestCount += 1
            }

            assertEquals(1, permissionRequestCount)
            assertTrue(store.startNotificationPairingAfterPermission)
            assertPairingNotStarted(store)

            actions.handleNotificationPermissionResult(
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )

            assertFalse(store.startNotificationPairingAfterPermission)
            assertTrue(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun grantedNotificationPermissionStartsPairingSession() {
        withPairingActions { store, actions ->
            denyNotificationPermission()
            actions.startNotificationPairing()

            actions.handleNotificationPermissionResult(PrivilegeUiPermissionState.Granted)

            assertFalse(store.startNotificationPairingAfterPermission)
            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
        }
    }

    @Test
    fun continuingAfterPermanentDenialStartsPairingWithoutNotification() {
        withPairingActions { store, actions ->
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
    fun cancellingAfterPermanentDenialKeepsPairingStoppedAndIgnoresStalePermissionResult() {
        withPairingActions { store, actions ->
            showPermanentDenialWarning(actions)

            actions.cancelPendingPairingStart()
            actions.handleNotificationPermissionResult(PrivilegeUiPermissionState.Granted)

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun permissionGateStopsExistingPairingBeforePermanentDenialCanBeCancelled() {
        withPairingActions { store, actions ->
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }
            denyNotificationPermission()

            actions.startNotificationPairing()

            assertPairingNotStarted(store)

            actions.handleNotificationPermissionResult(
                PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
            )
            actions.cancelPendingPairingStart()

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertPairingNotStarted(store)
        }
    }

    @Test
    fun ordinaryNotificationDenialStartsPairingWithoutNotification() {
        withPairingActions { store, actions ->
            denyNotificationPermission()
            actions.startNotificationPairing()

            actions.handleNotificationPermissionResult(
                PrivilegeUiPermissionState.NotGranted.Denied,
            )

            assertFalse(store.state.value.pairingNotificationPermissionWarningVisible)
            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
            assertTrue(store.state.value.startupLogLines.isNotEmpty())
        }
    }

    @Test
    fun stoppingFromNotificationClosesDialogAndPairingSession() {
        withPairingActions { store, actions ->
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
    fun unavailableNotificationUiKeepsViewModelPairingSessionOpen() {
        withPairingActions { store, actions ->
            store.updateState {
                it.copy(
                    pairingStatus = PrivilegeUiAdbPairingStatus.SEARCHING,
                    pairingDialogVisible = true,
                    notificationPairingRunning = true,
                )
            }

            actions.handleNotificationEvent(
                PrivilegeAdbPairingNotificationEvent.Unavailable(
                    ownerId = store.notificationPairingOwnerId,
                    message = "notification unavailable",
                ),
            )

            assertEquals(PrivilegeUiAdbPairingStatus.SEARCHING, store.state.value.pairingStatus)
            assertTrue(store.state.value.pairingDialogVisible)
            assertFalse(store.state.value.notificationPairingRunning)
        }
    }

    @Test
    fun notificationEventFromAnotherOwnerIsIgnored() {
        withPairingActions { store, actions ->
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

    private fun showPermanentDenialWarning(actions: PrivilegeUiAdbPairingActions) {
        denyNotificationPermission()
        actions.startNotificationPairing()
        actions.handleNotificationPermissionResult(
            PrivilegeUiPermissionState.NotGranted.PermanentlyDenied,
        )
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
        block: (PrivilegeUiViewModelStore, PrivilegeUiAdbPairingActions) -> Unit,
    ) {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.cancel()
        val actions = PrivilegeUiAdbPairingActions(
            store = store,
            coroutineScope = scope,
            enableTcpMode = {},
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
