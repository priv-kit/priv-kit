package priv.kit.core.internal.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import priv.kit.core.testing.TestBinder

class PrivilegeOwnerRestartPlannerTest {
    @Test
    fun pendingPlanBindsToOwnerAndIsConsumedOnce() {
        var nowMillis = 1_000L
        val planner = planner { nowMillis }
        val ownerBinder = TestBinder()
        planner.prepare(
            ownerBinder = null,
            ownerPid = 1234,
            passiveReconnectTimeoutMillis = 10_000L,
        )
        planner.bindOwner(ownerBinder)

        nowMillis = 5_000L

        assertEquals(
            PrivilegePlannedOwnerRestart(
                ownerPid = 1234,
                passiveReconnectTimeoutMillis = 10_000L,
            ),
            planner.consume(ownerBinder),
        )
        assertNull(planner.consume(ownerBinder))
    }

    @Test
    fun planExpiresWhenOwnerDoesNotDieInsideArmingWindow() {
        var nowMillis = 1_000L
        val ownerBinder = TestBinder()
        val planner = planner { nowMillis }
        planner.prepare(
            ownerBinder = ownerBinder,
            ownerPid = 1234,
            passiveReconnectTimeoutMillis = 10_000L,
        )

        nowMillis = 6_001L

        assertNull(planner.consume(ownerBinder))
    }

    @Test
    fun planDoesNotMatchAnotherOwnerBinder() {
        val expectedOwnerBinder = TestBinder()
        val planner = planner { 1_000L }
        planner.prepare(
            ownerBinder = expectedOwnerBinder,
            ownerPid = 1234,
            passiveReconnectTimeoutMillis = 10_000L,
        )

        assertNull(planner.consume(TestBinder()))
        assertNull(planner.consume(expectedOwnerBinder))
    }

    private fun planner(
        elapsedRealtime: () -> Long,
    ): PrivilegeOwnerRestartPlanner = PrivilegeOwnerRestartPlanner(
        armingWindowMillis = 5_000L,
        elapsedRealtime = elapsedRealtime,
    )
}
