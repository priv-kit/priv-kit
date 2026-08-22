package priv.kit.core.internal.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeOwnerCrashLoopGuardTest {
    @Test
    fun thirdOwnerDeathWithinWindowOpensCircuit() {
        val guard = guard()

        assertFalse(ownerSession(guard, linkedAtMillis = 0L, diedAtMillis = 1_000L).circuitOpen)
        assertFalse(ownerSession(guard, linkedAtMillis = 10_000L, diedAtMillis = 11_000L).circuitOpen)
        val decision = ownerSession(
            guard,
            linkedAtMillis = 20_000L,
            diedAtMillis = 21_000L,
        )

        assertTrue(decision.circuitOpen)
        assertTrue(decision.circuitOpened)
        assertFalse(decision.circuitReset)
    }

    @Test
    fun deathsAtWindowBoundaryDoNotAccumulate() {
        val guard = guard()

        ownerSession(guard, linkedAtMillis = 0L, diedAtMillis = 1_000L)
        ownerSession(guard, linkedAtMillis = 30_000L, diedAtMillis = 31_000L)
        val decision = ownerSession(
            guard,
            linkedAtMillis = 60_000L,
            diedAtMillis = 61_000L,
        )

        assertFalse(decision.circuitOpen)
        assertFalse(decision.circuitOpened)
    }

    @Test
    fun quickOwnerDeathKeepsOpenCircuitAfterWindowExpires() {
        val guard = openCircuit()

        val decision = ownerSession(
            guard,
            linkedAtMillis = 120_000L,
            diedAtMillis = 121_000L,
        )

        assertTrue(decision.circuitOpen)
        assertFalse(decision.circuitOpened)
        assertFalse(decision.circuitReset)
    }

    @Test
    fun stableOwnerSessionResetsOpenCircuit() {
        val guard = openCircuit()

        val decision = ownerSession(
            guard,
            linkedAtMillis = 30_000L,
            diedAtMillis = 90_000L,
        )

        assertFalse(decision.circuitOpen)
        assertFalse(decision.circuitOpened)
        assertTrue(decision.circuitReset)
    }

    private fun guard(): PrivilegeOwnerCrashLoopGuard =
        PrivilegeOwnerCrashLoopGuard(
            windowMillis = 60_000L,
            deathThreshold = 3,
        )

    private fun openCircuit(): PrivilegeOwnerCrashLoopGuard = guard().also { guard ->
        ownerSession(guard, linkedAtMillis = 0L, diedAtMillis = 1_000L)
        ownerSession(guard, linkedAtMillis = 10_000L, diedAtMillis = 11_000L)
        ownerSession(guard, linkedAtMillis = 20_000L, diedAtMillis = 21_000L)
    }

    private fun ownerSession(
        guard: PrivilegeOwnerCrashLoopGuard,
        linkedAtMillis: Long,
        diedAtMillis: Long,
    ): PrivilegeOwnerCrashLoopDecision {
        guard.onOwnerLinked(linkedAtMillis)
        return guard.onOwnerDied(diedAtMillis)
    }
}
