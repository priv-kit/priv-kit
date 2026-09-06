package priv.kit.core.internal.server

import org.junit.Assert.assertEquals
import org.junit.Test

class PrivilegeOwnerReconnectPlanTest {
    @Test
    fun activeReconnectWithoutPlannedRestartUsesOneActivePhase() {
        assertEquals(
            listOf(phase(PrivilegeOwnerReconnectMode.ACTIVE, 11_000L)),
            planOwnerReconnectPhases(
                startedAtMillis = 1_000L,
                followDeathDelayMillis = 10_000L,
                activeReconnect = true,
                plannedPassiveReconnectTimeoutMillis = null,
            ),
        )
    }

    @Test
    fun plannedRestartUsesPassiveThenActivePhasesInsideOriginalDeadline() {
        assertEquals(
            listOf(
                phase(PrivilegeOwnerReconnectMode.PASSIVE, 4_000L),
                phase(PrivilegeOwnerReconnectMode.ACTIVE, 11_000L),
            ),
            planOwnerReconnectPhases(
                startedAtMillis = 1_000L,
                followDeathDelayMillis = 10_000L,
                activeReconnect = true,
                plannedPassiveReconnectTimeoutMillis = 3_000L,
            ),
        )
    }

    @Test
    fun passiveTimeoutCoveringOriginalDeadlineDoesNotAddEmptyActivePhase() {
        assertEquals(
            listOf(phase(PrivilegeOwnerReconnectMode.PASSIVE, 11_000L)),
            planOwnerReconnectPhases(
                startedAtMillis = 1_000L,
                followDeathDelayMillis = 10_000L,
                activeReconnect = true,
                plannedPassiveReconnectTimeoutMillis = 20_000L,
            ),
        )
    }

    @Test
    fun disabledActiveReconnectStaysPassiveForOriginalDeadline() {
        assertEquals(
            listOf(phase(PrivilegeOwnerReconnectMode.PASSIVE, 11_000L)),
            planOwnerReconnectPhases(
                startedAtMillis = 1_000L,
                followDeathDelayMillis = 10_000L,
                activeReconnect = false,
                plannedPassiveReconnectTimeoutMillis = 3_000L,
            ),
        )
    }

    @Test
    fun deadlinesSaturateInsteadOfOverflowing() {
        assertEquals(
            listOf(phase(PrivilegeOwnerReconnectMode.ACTIVE, Long.MAX_VALUE)),
            planOwnerReconnectPhases(
                startedAtMillis = Long.MAX_VALUE - 5L,
                followDeathDelayMillis = 10L,
                activeReconnect = true,
                plannedPassiveReconnectTimeoutMillis = null,
            ),
        )
    }

    @Test
    fun processCandidateExcludesServerAndPreviousOwnerProcesses() {
        assertEquals(
            false,
            isOwnerReconnectCandidatePid(
                processPid = 100,
                serverPid = 200,
                excludedOwnerPid = 100,
            ),
        )
        assertEquals(
            false,
            isOwnerReconnectCandidatePid(
                processPid = 200,
                serverPid = 200,
                excludedOwnerPid = 100,
            ),
        )
        assertEquals(
            true,
            isOwnerReconnectCandidatePid(
                processPid = 300,
                serverPid = 200,
                excludedOwnerPid = 100,
            ),
        )
    }

    private fun phase(
        mode: PrivilegeOwnerReconnectMode,
        deadlineMillis: Long,
    ): PrivilegeOwnerReconnectPhase = PrivilegeOwnerReconnectPhase(
        mode = mode,
        deadlineMillis = deadlineMillis,
    )
}
