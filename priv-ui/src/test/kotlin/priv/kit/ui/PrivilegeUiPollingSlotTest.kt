package priv.kit.ui

import priv.kit.ui.state.PrivilegeUiPollingSlot

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PrivilegeUiPollingSlotTest {
    @Test
    fun pollingContinuesUntilAllLeasesClose() {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slot = PrivilegeUiPollingSlot(
            scope = scope,
            name = "test-polling-slot",
            dispatcher = Dispatchers.Unconfined,
            onStart = { starts.incrementAndGet() },
            onStop = { stops.incrementAndGet() },
        ) {
            awaitCancellation()
        }

        try {
            val first = slot.acquire()
            val second = slot.acquire()

            assertEquals(1, starts.get())
            assertEquals(0, stops.get())

            first.close()
            assertEquals(0, stops.get())

            second.close()
            assertEquals(1, stops.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun stopAllInvalidatesOutstandingLeases() {
        val stops = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val slot = PrivilegeUiPollingSlot(
            scope = scope,
            name = "test-polling-slot",
            dispatcher = Dispatchers.Unconfined,
            onStop = { stops.incrementAndGet() },
        ) {
            awaitCancellation()
        }

        try {
            val first = slot.acquire()
            val second = slot.acquire()

            slot.stopAll()
            first.close()
            second.close()

            assertEquals(1, stops.get())
        } finally {
            scope.cancel()
        }
    }
}
