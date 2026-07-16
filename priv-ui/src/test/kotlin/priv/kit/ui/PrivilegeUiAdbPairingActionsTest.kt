package priv.kit.ui

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
import org.robolectric.annotation.Config
import priv.kit.ui.adb.pairing.PrivilegeAdbPairingNotificationEvent
import priv.kit.ui.adb.pairing.PrivilegeUiAdbPairingActions
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiAdbPairingActionsTest {
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

    private fun withPairingActions(
        block: (PrivilegeUiViewModelStore, PrivilegeUiAdbPairingActions) -> Unit,
    ) {
        val store = PrivilegeUiViewModelStore(RuntimeEnvironment.getApplication())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
