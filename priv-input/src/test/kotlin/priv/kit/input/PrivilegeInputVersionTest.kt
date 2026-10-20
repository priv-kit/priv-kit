package priv.kit.input

import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 32, 33, 34, 35, 36], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeInputVersionTest {
    @Suppress("DEPRECATION")
    @Test fun ordinaryLongPressUsesVersionSpecificRepeatAndUpTimes() = runBlocking {
        val keys = mutableListOf<KeyEvent>()
        val received = mutableListOf<Long>()
        val input = inputFixture(inject = { event, _ ->
            received += System.nanoTime()
            keys += KeyEvent(event as KeyEvent)
            true
        })
        input.keyEvent(29, longPress = true)
        val timeout = ViewConfiguration.getLongPressTimeout().toLong()
        val old = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        if (!old) assertTrue(received[1] - received[0] >= (timeout - 1) * 1_000_000L)
        val down = keys.first().eventTime
        assertEquals(listOf(0, 0, 1), keys.map { it.action })
        assertEquals(down + if (old) ViewConfiguration.getGlobalActionKeyTimeout() else timeout, keys[1].eventTime)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) assertTrue(keys[2].eventTime >= down + timeout)
        else assertEquals(down, keys[2].eventTime)
        assertTrue(keys.all { it.source == InputDevice.SOURCE_UNKNOWN && it.downTime == down })
    }

    @Test fun swipePacingStartsAtAndroid16AndToolsStartAtAndroid12() = runBlocking {
        val events = mutableListOf<MotionEvent>()
        val input = inputFixture(inject = { event, _ ->
            events += MotionEvent.obtain(event as MotionEvent)
            true
        })
        input.swipe(0f, 0f, 25f, 50f, 100)
        try {
            assertTrue(events.all { it.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER && it.downTime == events.first().eventTime })
            assertTrue(events.last().eventTime - events.first().eventTime >= 91)
            assertEquals(MotionEvent.ACTION_UP, events.last().action)
            assertEquals(25f, events.last().x)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                // The 120 Hz schedule caps MOVE count even when injection returns immediately.
                assertTrue(events.count { it.action == MotionEvent.ACTION_MOVE } in 1..12)
                assertTrue(events[1].eventTime - events.first().eventTime >= 8)
            }
        } finally { events.forEach { it.recycle() } }
    }
}
