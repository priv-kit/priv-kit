package priv.kit.input

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeInputCommandFailureTest {
    @Test fun displayRestrictionStillPrecedesCommandParameterValidation() {
        val input = inputFixture()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { input.keyEvent(intArrayOf(), displayId = 2) }
        }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { input.tap(Float.NaN, 0f, displayId = 2) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { input.keyEvent(intArrayOf()) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { input.tap(Float.NaN, 0f) }
        }
    }

    @Test fun interruptedDragReleasesContact() = kotlinx.coroutines.runBlocking {
        val pressed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = mutableListOf<Int>()
        val input = inputFixture(inject = { event, _ ->
            actions += (event as MotionEvent).action
            if (event.action == MotionEvent.ACTION_DOWN) pressed.complete(Unit)
            true
        })
        val job = launch { input.dragAndDrop(0f, 0f, 1f, 1f, 5_000) }
        kotlinx.coroutines.withTimeout(5_000) { pressed.await(); job.cancelAndJoin() }
        assertEquals(listOf(0, 3), actions)
    }

    @Test fun outOfRangeDurationsFailBeforeInjection() {
        var injected = 0
        val input = inputFixture(inject = { _, _ -> injected++; true })
        for (duration in listOf(Int.MAX_VALUE.toLong() + 1, Int.MIN_VALUE.toLong() - 1)) {
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.swipe(0f, 0f, 1f, 1f, duration) } }
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.dragAndDrop(0f, 0f, 1f, 1f, duration) } }
            assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.keyCombination(intArrayOf(113, 29), duration) } }
        }
        assertEquals(0, injected)
    }
}
