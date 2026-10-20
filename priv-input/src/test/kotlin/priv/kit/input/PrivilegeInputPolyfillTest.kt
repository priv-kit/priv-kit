package priv.kit.input

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import android.os.RemoteException
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.P, Build.VERSION_CODES.R,
    Build.VERSION_CODES.S, Build.VERSION_CODES.S_V2, Build.VERSION_CODES.UPSIDE_DOWN_CAKE],
    manifest = Config.NONE, shadows = [RealtimeSystemClock::class, DisplayMotionEventShadow::class])
class PrivilegeInputPolyfillTest {
    @Test fun durationDoubleTapAsyncAndDelayPreserveTheWholeSequence() {
        val keys = mutableListOf<KeyEvent>()
        val modes = mutableListOf<Int>()
        val input = inputFixture(inject = { event, mode ->
            keys += KeyEvent(event as KeyEvent); modes += mode; true
        })
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        kotlinx.coroutines.runBlocking { input.keyEvent(intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B), durationMillis = timeout + 20,
            doubleTap = true, async = true, delayMillis = 50, source = InputDevice.SOURCE_KEYBOARD) }
        assertEquals(listOf(29, 29, 29, 29, 29, 29, 30, 30, 30, 30, 30, 30), keys.map { it.keyCode })
        assertEquals(List(4) { listOf(0, 0, 1) }.flatten(), keys.map { it.action })
        assertTrue(modes.all { it == 0 })
        assertTrue(keys.all { it.source == InputDevice.SOURCE_KEYBOARD })
        keys.chunked(3).forEach { (down, repeat, up) ->
            assertEquals(down.eventTime + timeout, repeat.eventTime)
            assertEquals(KeyEvent.FLAG_LONG_PRESS, repeat.flags)
            assertEquals(1, repeat.repeatCount)
            assertEquals(down.downTime, up.downTime)
            assertEquals(down.eventTime, up.eventTime) // AOSP preserves the original UP time.
        }
        val downs = keys.chunked(3).map { it.first().eventTime }
        for ((index, pair) in downs.zipWithNext().withIndex()) {
            assertTrue(pair.second - pair.first >= timeout + 20 + if (index == 1) 50 else 40)
        }
    }

    @Test fun customChordCarriesModifiersAndReleasesOnTheSameDisplay() {
        val keys = mutableListOf<KeyEvent>()
        val modes = mutableListOf<Int>()
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 3 else -1
        val times = mutableListOf<Long>()
        val input = inputFixture(inject = { event, mode ->
            times += System.nanoTime()
            keys += KeyEvent(event as KeyEvent); modes += mode; true
        })
        kotlinx.coroutines.runBlocking { input.keyCombination(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A), 80,
            InputDevice.SOURCE_KEYBOARD, target) }
        assertTrue(times[2] - times[1] >= 80_000_000)
        val ctrl = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        assertEquals(listOf(0, 0, 1, 1), keys.map { it.action })
        assertEquals(listOf(113, 29, 113, 29), keys.map { it.keyCode })
        assertEquals(listOf(0, ctrl, ctrl, 0), keys.map { it.metaState })
        assertEquals(listOf(0, 0, 0, 0), modes)
        assertTrue(keys.all { it.source == InputDevice.SOURCE_KEYBOARD })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assertTrue(keys.all { it.javaClass.getMethod("getDisplayId").invoke(it) == target })
    }

    @Test @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.R])
    fun defaultChordUsesAndroid12HoldAndModifierBehavior() {
        val keys = mutableListOf<KeyEvent>()
        val times = mutableListOf<Long>()
        val input = inputFixture(inject = { event, _ ->
            times += System.nanoTime()
            keys += KeyEvent(event as KeyEvent); true
        })
        kotlinx.coroutines.runBlocking { input.keyCombination(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A)) }
        assertEquals(listOf(0, 0, 1, 1), keys.map { it.action })
        assertTrue(keys.all { it.metaState == 0 && it.source == InputDevice.SOURCE_UNKNOWN })
        assertTrue(times[2] - times[1] >= ViewConfiguration.getTapTimeout() * 1_000_000L)
    }

    @Test fun scrollPreservesAxesToolCoordinatesAndRouting() {
        val events = mutableListOf<MotionEvent>()
        val displays = mutableListOf<Int>()
        val input = inputFixture(inject = { event, mode ->
            assertEquals(2, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) displays += event.javaClass.getMethod("getDisplayId").invoke(event) as Int
            events += MotionEvent.obtain(event as MotionEvent); true
        })
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 3 else -1
        kotlinx.coroutines.runBlocking { input.scroll(10f, 20f, mapOf(MotionEvent.AXIS_HSCROLL to 2f, MotionEvent.AXIS_VSCROLL to -3f),
            InputDevice.SOURCE_MOUSE, target) }
        kotlinx.coroutines.runBlocking { input.scroll(100f, 200f, mapOf(MotionEvent.AXIS_SCROLL to 4f)) }
        kotlinx.coroutines.runBlocking { input.scroll() }
        try {
            assertEquals(listOf(MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_SCROLL, MotionEvent.ACTION_SCROLL), events.map { it.action })
            val mouse = events[0]
            assertEquals(10f, mouse.x); assertEquals(20f, mouse.y)
            assertEquals(MotionEvent.TOOL_TYPE_MOUSE, mouse.getToolType(0))
            assertEquals(2f, mouse.getAxisValue(MotionEvent.AXIS_HSCROLL))
            assertEquals(-3f, mouse.getAxisValue(MotionEvent.AXIS_VSCROLL))
            assertEquals(InputDevice.SOURCE_MOUSE, mouse.source)
            assertEquals(InputDevice.SOURCE_ROTARY_ENCODER, events[1].source)
            assertEquals(0f, events[1].x); assertEquals(0f, events[1].y)
            assertEquals(4f, events[1].getAxisValue(MotionEvent.AXIS_SCROLL))
            assertEquals(0f, events[2].getAxisValue(MotionEvent.AXIS_SCROLL))
            assertTrue(events.all { it.pressure == 0f && it.size == 1f })
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) assertEquals(listOf(target, -1, -1), displays)
        } finally { events.forEach { it.recycle() } }
    }

    @Test fun interruptedDurationReleasesKeyWithoutReplay() = kotlinx.coroutines.runBlocking {
        val pressed = kotlinx.coroutines.CompletableDeferred<Unit>()
        val actions = mutableListOf<Int>()
        val input = inputFixture(inject = { event, _ ->
            actions += (event as KeyEvent).action
            if (event.action == KeyEvent.ACTION_DOWN) pressed.complete(Unit)
            true
        })
        val job = launch { input.keyEvent(29, durationMillis = 5_000, doubleTap = true) }
        kotlinx.coroutines.withTimeout(5_000) { pressed.await() }
        kotlinx.coroutines.delay(100)
        assertTrue(job.isActive)
        kotlinx.coroutines.withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), actions)
    }

    @Test @Config(sdk = [Build.VERSION_CODES.O, Build.VERSION_CODES.S])
    fun failedChordReleasesEveryAcceptedKeyAndPreservesFailure() {
        val events = mutableListOf<Pair<Int, Int>>()
        val input = inputFixture(inject = { event, _ ->
            val key = event as KeyEvent
            events += key.keyCode to key.action
            if (key.keyCode == KeyEvent.KEYCODE_A || key.action == KeyEvent.ACTION_UP) throw RemoteException("key ${key.keyCode}")
            true
        })
        val error = assertThrows(RemoteException::class.java) {
            kotlinx.coroutines.runBlocking { input.keyCombination(intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_A), 80) }
        }
        assertEquals("key 29", error.message)
        assertEquals(listOf(113 to 0, 59 to 0, 29 to 0, 113 to 1, 59 to 1), events)
        assertEquals("key 113", error.suppressed.single().message)
        assertEquals("key 59", error.suppressed.single().suppressed.single().message)
    }

}
