package priv.kit.ui.m3

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import priv.kit.ui.*

@OptIn(ExperimentalCoroutinesApi::class)
class PrivilegeM3PermissionFlowTest {
    @Test
    fun batteryFlowChecksExemptionAfterSettingsReturns() = runTest {
        val returned = CompletableDeferred<Unit>()
        var restricted = true
        val result = async {
            runPrivilegeM3BatteryOptimizationFlow(
                restricted = { restricted }, explain = { true },
                request = { returned.await(); !restricted },
            )
        }
        runCurrent()
        assertFalse(result.isCompleted)
        restricted = false
        returned.complete(Unit)
        assertTrue(result.await())
    }

    @Test
    fun existingBatteryExemptionSkipsGuidance() = runTest {
        assertTrue(runPrivilegeM3BatteryOptimizationFlow(
            restricted = { false }, explain = { error("Already exempt") },
            request = { error("Already exempt") },
        ))
        assertFalse(runPrivilegeM3BatteryOptimizationFlow(
            restricted = { true }, explain = { false },
            request = { error("User cancelled") },
        ))
    }

    @Test
    fun batteryFlowReturnsPlatformResultWithoutRecheckingRestriction() = runTest {
        for (granted in listOf(true, false)) {
            var checks = 0
            assertEquals(granted, runPrivilegeM3BatteryOptimizationFlow(
                restricted = { checks++; true },
                explain = { true },
                request = { granted },
            ))
            assertEquals(1, checks)
        }
    }

    @Test
    fun settingsFlowWaitsForReturnThenChecksActualGrant() = runTest {
        val returned = CompletableDeferred<Unit>()
        var granted = false
        var checked = false
        val result = async {
            runPrivilegeM3PermissionFlow(
                request = { false },
                requiresSettings = { true }, explain = { true },
                openSettings = { returned.await() },
                granted = { checked = true; granted },
            )
        }
        runCurrent()
        assertFalse(result.isCompleted)
        assertFalse(checked)
        granted = true
        returned.complete(Unit)
        assertEquals(true, result.await())
        assertTrue(checked)
    }

    @Test
    fun cancellingExplanationDoesNotOpenSettings() = runTest {
        try {
            runPrivilegeM3PermissionFlow(
                request = { false },
                requiresSettings = { true }, explain = { false },
                openSettings = { error("Must not open settings") }, granted = { error("Must not check") },
            )
            fail("Expected coroutine cancellation")
        } catch (_: CancellationException) { }
    }

    @Test
    fun cancellationPropagatesIntoSettingsFlow() = runTest {
        var closed = false
        val operation = launch {
            runPrivilegeM3PermissionFlow(
                request = { false },
                requiresSettings = { true }, explain = { true }, granted = { false },
                openSettings = { try { awaitCancellation() } finally { closed = true } },
            )
        }
        runCurrent()
        operation.cancelAndJoin()
        assertTrue(closed)
    }

    @Test
    fun ordinaryDenialDoesNotRequireAnExplanation() = runTest {
        assertEquals(false, runPrivilegeM3PermissionFlow(
            request = { false }, requiresSettings = { false },
            explain = { error("No settings flow") }, openSettings = { error("No settings flow") },
            granted = { false },
        ))
    }
}
