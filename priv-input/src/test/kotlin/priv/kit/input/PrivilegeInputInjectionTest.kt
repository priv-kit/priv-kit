package priv.kit.input

import java.io.IOException

import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.os.RemoteException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeInputInjectionTest {
    private val events = mutableListOf<InputEvent>()
    private val input = inputFixture(
        inject = { event, _ ->
            events += when (event) {
                is KeyEvent -> KeyEvent(event)
                is MotionEvent -> MotionEvent.obtain(event)
                else -> error("Unexpected event")
            }
            true
        },
    )

    @Test fun keyAndLongPressUseAndroidEvents() {
        kotlinx.coroutines.runBlocking { input.keyEvent(KeyEvent.KEYCODE_A) }
        kotlinx.coroutines.runBlocking { input.keyEvent(KeyEvent.KEYCODE_B, longPress = true) }
        val keys = events.map { it as KeyEvent }
        assertEquals(listOf(0, 1, 0, 0, 1), keys.map { it.action })
        assertEquals(listOf(29, 29, 30, 30, 30), keys.map { it.keyCode })
        assertTrue(keys.all { it.source == InputDevice.SOURCE_KEYBOARD })
        assertEquals(1, keys[3].repeatCount)
        assertTrue(keys[3].flags and KeyEvent.FLAG_LONG_PRESS != 0)
        assertEquals(keys[2].eventTime, keys[3].eventTime)
        assertEquals(keys[2].downTime, keys[4].downTime)
    }

    @Test fun tapAndSwipePreserveCoordinatesAndTiming() {
        kotlinx.coroutines.runBlocking { input.tap(12.5f, 24f) }
        kotlinx.coroutines.runBlocking { input.swipe(10f, 20f, 110f, 220f, 64) }
        val motion = events.map { it as MotionEvent }
        assertEquals(listOf(0, 1, 0), motion.take(3).map { it.action })
        assertTrue(motion.drop(3).dropLast(1).all { it.action == MotionEvent.ACTION_MOVE })
        assertEquals(MotionEvent.ACTION_UP, motion.last().action)
        assertEquals(12.5f, motion[0].x)
        assertEquals(24f, motion[1].y)
        assertEquals(10f, motion[2].x)
        assertEquals(20f, motion[2].y)
        assertEquals(110f, motion.last().x)
        assertEquals(220f, motion.last().y)
        assertTrue(motion.last().eventTime - motion[2].eventTime >= 64)
        assertTrue(motion.all { it.source == InputDevice.SOURCE_TOUCHSCREEN })
        assertTrue(motion.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN })
        assertEquals(0f, motion.last().pressure)
    }

    @Test fun invalidInputIsRejectedBeforeInjection() {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.keyEvent(-1) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.tap(Float.NaN, 1f) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.swipe(0f, 0f, Float.POSITIVE_INFINITY, 1f, 1) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.motionEvent(99, 0f, 0f) } }
        assertTrue(events.isEmpty())
    }

    @Test fun failedSwipeCancelsContactAndRetainsOriginalFailure() {
        val failure = RemoteException("Move rejected")
        val cleanupFailure = RemoteException("Cancel rejected")
        val actions = mutableListOf<Int>()
        val injector = inputFixture(inject = { event, _ ->
            val action = (event as MotionEvent).action
            actions += action
            when (action) {
                MotionEvent.ACTION_MOVE -> throw failure
                MotionEvent.ACTION_CANCEL -> throw cleanupFailure
            }
            true
        })
        assertSame(failure, assertThrows(RemoteException::class.java) { kotlinx.coroutines.runBlocking { injector.swipe(0f, 0f, 100f, 100f, 64, 0, -1) } })
        assertEquals(listOf(0, 2, 3), actions)
        assertSame(cleanupFailure, failure.suppressed.single())
    }

    @Test fun deadBinderSkipsTouchAndKeyCleanup() {
        for (key in listOf(false, true)) {
            val failure = android.os.DeadObjectException("Forwarder died")
            var calls = 0
            val injector = inputFixture(inject = { _, _ ->
                if (++calls == 2) throw failure
                true
            })
            assertSame(failure, assertThrows(android.os.DeadObjectException::class.java) {
                kotlinx.coroutines.runBlocking {
                    if (key) injector.keyEvent(KeyEvent.KEYCODE_A, longPress = true)
                    else injector.swipe(0f, 0f, 100f, 100f, 64)
                }
            })
            assertEquals(2, calls)
            assertTrue(failure.suppressed.isEmpty())
        }
    }

    @Test fun falseInjectionResultThrowsIOException() {
        val input = inputFixture(inject = { _, _ -> false })
        val failure = assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { input.tap(0f, 0f) } }
        assertEquals("Input event injection failed", failure.message)
    }

    @Test fun failedLongPressStillReleasesKey() {
        val actions = mutableListOf<Int>()
        val failure = RemoteException("Repeat rejected")
        val injector = inputFixture(inject = { event, _ ->
            val key = event as KeyEvent
            actions += key.action
            if (key.repeatCount == 1) throw failure
            true
        })
        assertSame(failure, assertThrows(RemoteException::class.java) {
            kotlinx.coroutines.runBlocking { injector.keyEvent(intArrayOf(29), true, 0, false, false, 0, 0, -1) }
        })
        assertEquals(listOf(0, 0, 1), actions)
    }
}
