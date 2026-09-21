package priv.kit.ui.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeUiRefreshTaskTest {
    @Test
    fun concurrentRequestsShareOneRefresh() = runTest {
        val task = PrivilegeUiRefreshTask()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val callers = List(8) { launch { task.run { calls++; finish.await() } } }
        testScheduler.runCurrent()
        assertEquals(1, calls)
        finish.complete(Unit)
        callers.forEach { it.join() }
        assertEquals(1, calls)
        task.run { calls++ }
        assertEquals(2, calls)
    }

    @Test
    fun cancelledWaiterDoesNotCloseAnotherCallersSession() = runTest {
        val task = PrivilegeUiRefreshTask()
        val finish = CompletableDeferred<Unit>()
        var released = false
        val owner = launch { task.run { finish.await() } }
        val waiter = launch { task.run { error("Unexpected duplicate refresh") } }
        testScheduler.runCurrent()
        waiter.cancelAndJoin()
        task.releaseWhenIdle { released = true }
        assertFalse(released)
        assertTrue(owner.isActive)
        finish.complete(Unit)
        owner.join()
        assertTrue(released)
    }

    @Test
    fun survivingCallerTakesOverWhenOriginalPageLeaves() = runTest {
        val task = PrivilegeUiRefreshTask()
        var calls = 0
        val owner = launch { task.run { calls++; awaitCancellation() } }
        testScheduler.runCurrent()
        val survivor = launch { task.run { calls++ } }
        testScheduler.runCurrent()
        owner.cancelAndJoin()
        survivor.join()
        assertEquals(2, calls)
    }
}
