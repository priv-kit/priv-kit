package priv.kit.ui

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeUiPermissionCoordinatorTest {
    private fun coordinator(
        onClose: () -> Unit,
        handler: suspend (PrivilegeUiAccessRequest) -> Boolean,
    ) = PrivilegeUiPermissionCoordinator(
        acquireInteractivePermit = { AutoCloseable(onClose) },
        interactionsEnabled = { true }, ownerClosed = { false },
        cancelPairingWithoutInteractionHost = {}, handler = handler,
    )

    @Test
    fun configurationDetachKeepsTheSameFlowAndPermit() = runTest {
        var closed = 0
        var calls = 0
        val answer = CompletableDeferred<Boolean>()
        val coordinator = coordinator({ closed++ }) { calls++; answer.await() }
        coordinator.registerHost()
        val request = async { coordinator.requestNotificationPermission() }
        runCurrent()
        coordinator.unregisterHost(changingConfigurations = true)
        runCurrent()
        assertFalse(request.isCompleted)
        assertEquals(0, closed)
        coordinator.registerHost()
        answer.complete(false)
        assertFalse(request.await())
        assertEquals(1, calls)
        assertEquals(1, closed)
        coordinator.close()
        assertEquals(1, closed)
    }

    @Test
    fun leavingPageCancelsCustomFlowAndReleasesPermit() = runTest {
        var closed = 0
        var cancelled = false
        val coordinator = coordinator({ closed++ }) {
            try { awaitCancellation() } finally { cancelled = true }
        }
        coordinator.registerHost()
        val result = async { coordinator.requestLocalNetworkPermission() }
        runCurrent()
        coordinator.unregisterHost(changingConfigurations = false)
        kotlin.test.assertFailsWith<CancellationException> { result.await() }
        assertTrue(cancelled)
        assertEquals(1, closed)
    }

    @Test
    fun userCancellationDoesNotPreventTheNextRequest() = runTest {
        var calls = 0
        var closed = 0
        val coordinator = coordinator({ closed++ }) {
            if (++calls == 1) throw CancellationException("User cancelled")
            true
        }
        coordinator.registerHost()
        kotlin.test.assertFailsWith<CancellationException> { coordinator.requestNotificationPermission() }
        assertTrue(coordinator.requestLocalNetworkPermission())
        assertEquals(2, closed)
    }

    @Test
    fun batteryAndPermissionsAreSerializedInOneFlow() = runTest {
        val answer = CompletableDeferred<Boolean>()
        val requests = mutableListOf<PrivilegeUiAccessRequest>()
        val coordinator = coordinator({}) { requests += it; answer.await() }
        coordinator.registerHost()
        val permission = async { coordinator.requestNotificationPermission() }
        val battery = async { coordinator.request(PrivilegeUiAccessRequest.BatteryOptimization) }
        runCurrent()
        assertEquals(listOf(PrivilegeUiAccessRequest.Permission.Notifications), requests)
        answer.complete(true)
        assertTrue(permission.await())
        assertTrue(battery.await())
        assertEquals(PrivilegeUiAccessRequest.BatteryOptimization, requests.last())
    }

    @Test
    fun closedOrDetachedHostRejectsNewRequests() = runTest {
        val coordinator = coordinator({}) { error("No host") }
        kotlin.test.assertFailsWith<CancellationException> { coordinator.requestNotificationPermission() }
        coordinator.registerHost()
        coordinator.close()
        coordinator.registerHost()
        kotlin.test.assertFailsWith<CancellationException> { coordinator.requestNotificationPermission() }
    }
}
