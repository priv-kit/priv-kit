package priv.kit.input

import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 31, 34, 36], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeInputCancellationTest {
    @Test fun cancellingSwipeReleasesContactAndAllowsNextTap() = runBlocking {
        val actions = mutableListOf<Int>()
        lateinit var gesture: Job
        var cancel = true
        val input = inputFixture(inject = { event, _ ->
            val action = (event as MotionEvent).action
            actions += action
            if (cancel && action == MotionEvent.ACTION_MOVE) gesture.cancel()
            true
        })
        gesture = launch(start = CoroutineStart.LAZY) { input.swipe(0f, 0f, 100f, 100f, 5_000) }
        gesture.start()
        withTimeout(1_000) { gesture.join() }
        assertTrue(gesture.isCancelled)
        assertEquals(listOf(0, 2, 3), actions)
        cancel = false
        input.tap(10f, 10f)
        assertEquals(listOf(0, 2, 3, 0, 1), actions)
    }

    @Test fun cancellingDuringHoldReleasesAllChordKeys() = runBlocking {
        val holding = CompletableDeferred<Unit>()
        val released = CompletableDeferred<Unit>()
        val events = mutableListOf<Pair<Int, Int>>()
        val input = inputFixture(inject = { event, _ ->
            val key = event as KeyEvent
            events += key.keyCode to key.action
            if (key.keyCode == 29 && key.action == KeyEvent.ACTION_DOWN) holding.complete(Unit)
            if (key.action == KeyEvent.ACTION_UP) released.complete(Unit)
            true
        })
        val gesture = launch { input.keyCombination(intArrayOf(113, 29), durationMillis = 5_000) }
        withTimeout(5_000) { holding.await() }
        delay(100)
        assertTrue(gesture.isActive)
        assertFalse(released.isCompleted)
        withTimeout(1_000) { gesture.cancelAndJoin() }
        assertTrue(gesture.isCancelled)
        assertEquals(listOf(113 to 0, 29 to 0, 113 to 1, 29 to 1), events)
    }

    @Test fun cancellingTapBetweenDownAndUpSendsCancel() = runBlocking {
        lateinit var gesture: Job
        val actions = mutableListOf<Int>()
        val input = inputFixture(inject = { event, _ ->
            actions += (event as MotionEvent).action
            if (event.action == MotionEvent.ACTION_DOWN) gesture.cancel()
            true
        })
        gesture = launch(start = CoroutineStart.LAZY) { input.tap(1f, 2f) }
        gesture.start()
        withTimeout(5_000) { gesture.join() }
        assertEquals(listOf(0, 3), actions)
    }

    @Test fun cancellingTextReleasesPressedKeyAndStopsRemainingText() = runBlocking {
        lateinit var gesture: Job
        val events = mutableListOf<Pair<Int, Int>>()
        val input = inputFixture(inject = { event, _ ->
            val key = event as KeyEvent
            events += key.keyCode to key.action
            if (key.action == KeyEvent.ACTION_DOWN) gesture.cancel()
            true
        })
        gesture = launch(start = CoroutineStart.LAZY) { input.text("abc") }
        gesture.start()
        withTimeout(5_000) { gesture.join() }
        assertEquals(listOf(29 to 0, 29 to 1), events)
    }

    @Test fun alreadyCancelledCallInjectsNothing() = runBlocking {
        var observedCancellation = false
        val job = launch {
            currentCoroutineContext().cancel()
            try { inputFixture().tap(0f, 0f); fail("Expected cancellation") }
            catch (_: CancellationException) { observedCancellation = true }
        }
        job.join()
        assertTrue(observedCancellation)
        assertTrue(job.isCancelled)
    }
}
