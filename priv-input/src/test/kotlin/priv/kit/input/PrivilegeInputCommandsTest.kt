package priv.kit.input

import android.os.Build
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 27, 28, 29, 30], manifest = Config.NONE, shadows = [RealtimeSystemClock::class, DisplayMotionEventShadow::class])
class PrivilegeInputCommandsTest {
    private val events = mutableListOf<InputEvent>()
    private val modes = mutableListOf<Int>()
    private val input = inputFixture(
        inject = { event, mode ->
            val copy = if (event is KeyEvent) KeyEvent(event) else MotionEvent.obtain(event as MotionEvent)
            if (Build.VERSION.SDK_INT >= 29) {
                copy.javaClass.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(copy, event.displayId)
            }
            events += copy
            modes += mode
            true
        },
    )

    @Test fun motionUsesMatchingSystemInputDevice() {
        val device = org.robolectric.shadows.ShadowInputDevice.makeInputDeviceNamed("test stylus")
        org.robolectric.util.ReflectionHelpers.setField(device, "mId", 42)
        org.robolectric.util.ReflectionHelpers.setField(device, "mSources", InputDevice.SOURCE_STYLUS)
        val manager = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(android.hardware.input.InputManager::class.java)
        org.robolectric.Shadows.shadowOf(manager).addInputDevice(device)
        kotlinx.coroutines.runBlocking { input.tap(1f, 2f, source = InputDevice.SOURCE_STYLUS) }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
            events.map { (it as MotionEvent).action })
        assertTrue(events.all { it.deviceId == 42 })
        events.clear()
        kotlinx.coroutines.runBlocking { input.tap(1f, 2f, source = InputDevice.SOURCE_MOUSE) }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP),
            events.map { (it as MotionEvent).action })
        assertTrue(events.all { it.deviceId == 0 })
    }

    @Test fun keyDefaultsLongPressAndNamesFollowVersion() {
        kotlinx.coroutines.runBlocking { input.keyEvent(arrayOf("A", "KEYCODE_B", "31"), longPress = true) }
        val keys = events.map { it as KeyEvent }
        assertEquals(listOf(29, 29, 29, 30, 30, 30, 31, 31, 31), keys.map { it.keyCode })
        assertEquals(listOf(0, 0, 1, 0, 0, 1, 0, 0, 1), keys.map { it.action })
        assertTrue(keys.all { it.source == if (Build.VERSION.SDK_INT < 29) InputDevice.SOURCE_KEYBOARD else 0 })
        keys.chunked(3).forEach {
            assertEquals(it[0].eventTime, it[1].eventTime)
            assertEquals(it[0].eventTime, it[2].eventTime)
            assertEquals(1, it[1].repeatCount)
            assertEquals(KeyEvent.FLAG_LONG_PRESS, it[1].flags)
            assertEquals(0, it[2].repeatCount)
        }
        assertTrue(modes.all { it == 2 })
    }

    @Test fun swipePreservesLegacyTimestampsAndInterpolation() {
        kotlinx.coroutines.runBlocking { input.swipe(0f, 0f, 40f, 80f, 40) }
        val motion = events.map { it as MotionEvent }
        val down = motion.first().eventTime
        assertEquals(MotionEvent.ACTION_DOWN, motion.first().action)
        assertEquals(MotionEvent.ACTION_UP, motion.last().action)
        assertTrue(motion.last().eventTime - down >= 40)
        assertTrue(motion.zipWithNext().all { (a, b) -> a.eventTime <= b.eventTime })
        motion.filter { it.action == MotionEvent.ACTION_MOVE }.forEach {
            assertEquals((it.eventTime - down).toFloat(), it.x, 0.001f)
            assertEquals(it.x * 2, it.y, 0.001f)
        }
        assertTrue(motion.all { it.downTime == if (Build.VERSION.SDK_INT < 29) it.eventTime else down })
        assertTrue(motion.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN && it.deviceId == 0 })
        assertEquals(40f, motion.last().x)
        assertEquals(0f, motion.last().pressure)
    }

    @Test fun dragDurationExcludesHoldBeforeAndroid10() {
        kotlinx.coroutines.runBlocking { input.dragAndDrop(0f, 0f, 40f, 80f, 40) }
        val motion = events.map { it as MotionEvent }
        val hold = ViewConfiguration.getLongPressTimeout().toLong()
        val old = Build.VERSION.SDK_INT < 29
        assertTrue(motion.last().eventTime - motion.first().eventTime >= hold + if (old) 40 else 0)
        assertEquals(old, motion.any { it.action == MotionEvent.ACTION_MOVE })
    }

    @Test fun longDragPreservesMovementWindowInterpolationAndDownTime() {
        val hold = ViewConfiguration.getLongPressTimeout().toLong()
        val duration = hold + 500
        kotlinx.coroutines.runBlocking { input.dragAndDrop(0f, 0f, 40f, 80f, duration) }
        val motion = events.map { it as MotionEvent }
        val moves = motion.filter { it.action == MotionEvent.ACTION_MOVE }
        assertFalse(moves.isEmpty())
        val down = motion.first().eventTime
        assertTrue(moves.first().eventTime - down >= hold)
        val old = Build.VERSION.SDK_INT < 29
        val start = if (old) moves.first().eventTime else down
        assertTrue(motion.last().eventTime - start >= duration)
        moves.forEach {
            val alpha = (it.eventTime - start).toFloat() / duration
            assertEquals(40f * alpha, it.x, 0.001f)
            assertEquals(80f * alpha, it.y, 0.001f)
        }
        assertTrue(motion.all { it.downTime == if (old) it.eventTime else down })
        assertEquals(MotionEvent.ACTION_UP, motion.last().action)
        assertEquals(40f, motion.last().x)
        assertEquals(80f, motion.last().y)
        assertEquals(0f, motion.last().pressure)
    }

    @Test fun tapRollAndTextKeepLegacySourcesAndTools() {
        kotlinx.coroutines.runBlocking { input.tap(10f, 20f, source = InputDevice.SOURCE_STYLUS) }
        kotlinx.coroutines.runBlocking { input.press() }
        kotlinx.coroutines.runBlocking { input.roll(-3f, 4f) }
        val motion = events.map { it as MotionEvent }
        assertEquals(listOf(0, 1, 0, 1, 2), motion.map { it.action })
        assertTrue(motion.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_UNKNOWN })
        assertEquals(InputDevice.SOURCE_STYLUS, motion[0].source)
        assertEquals(InputDevice.SOURCE_TRACKBALL, motion.last().source)
        assertEquals(0f, motion.last().pressure)
        events.clear()
        kotlinx.coroutines.runBlocking { input.text("a%sb") }
        assertEquals(listOf(29, 29, 62, 62, 30, 30), events.map { (it as KeyEvent).keyCode })
        assertTrue(events.all { it.source == InputDevice.SOURCE_KEYBOARD })
    }

    @Test fun motionCommandsAreBackportedButDisplaysStillRequireAndroid10() {
        kotlinx.coroutines.runBlocking { input.motionEvent(MotionEvent.ACTION_DOWN, 5f, 8f) }
        assertEquals(MotionEvent.ACTION_DOWN, (events.single() as MotionEvent).action)
        events.clear()
        kotlinx.coroutines.runBlocking { input.motionEvent(MotionEvent.ACTION_CANCEL) }
        assertEquals(MotionEvent.ACTION_CANCEL, (events.single() as MotionEvent).action)
        assertEquals(0f, (events.single() as MotionEvent).pressure)
        events.clear()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { input.tap(1f, 2f, displayId = 2) } }
            assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { input.scroll(displayId = 2) } }
            assertTrue(events.isEmpty())
        } else {
            kotlinx.coroutines.runBlocking { input.motionEvent(MotionEvent.ACTION_MOVE, 5f, 8f, displayId = 2) }
            assertEquals(2, events.single().displayId)
            assertEquals(1f, (events.single() as MotionEvent).pressure)
            events.clear()
            kotlinx.coroutines.runBlocking { input.tap(1f, 2f) }
            assertTrue(events.all { it.displayId == 0 })
            events.clear()
            kotlinx.coroutines.runBlocking { input.keyEvent(29) }
            assertTrue(events.all { it.displayId == -1 })
        }
    }

}

private val InputEvent.displayId: Int get() = javaClass.getMethod("getDisplayId").invoke(this) as Int

// Robolectric 4.17 ShadowMotionEvent does not implement get/setDisplayId.
// Model only that field here; real routing is verified with a secondary emulator display.
@org.robolectric.annotation.Implements(MotionEvent::class)
class DisplayMotionEventShadow : org.robolectric.shadows.ShadowMotionEvent() {
    private var targetDisplay = 0
    @org.robolectric.annotation.Implementation(minSdk = 29)
    fun setDisplayId(value: Int) { targetDisplay = value }
    @org.robolectric.annotation.Implementation(minSdk = 29)
    fun getDisplayId(): Int = targetDisplay
}
