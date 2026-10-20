package priv.kit.input

import android.os.Binder
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.input.internal.InputCommands
import priv.kit.input.internal.InputShellCommandCompat

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], manifest = Config.NONE, shadows = [RealtimeSystemClock::class])
class PrivilegeInputCompatShellTest {
    @Test fun tapUsesSourceAndCoordinatesWithoutShellService() {
        val events = mutableListOf<MotionEvent>()
        val input = inputFixture(inject = { e, _ -> events.add(MotionEvent.obtain(e as MotionEvent)); true })
        assertEquals("", input.compatShellCommand("mouse", "-d", "DeFaUlT_dIsPlAy", "tap", "12", "34"))
        try {
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events.map { it.action })
            assertTrue(events.all { it.source == InputDevice.SOURCE_MOUSE && it.x == 12f && it.y == 34f })
        } finally { events.forEach { it.recycle() } }
    }

    @Test fun newKeyOptionsAreBackfilled() {
        val events = mutableListOf<KeyEvent>()
        val input = inputFixture(inject = { e, mode ->
            assertEquals(0, mode); events.add(KeyEvent(e as KeyEvent)); true
        })
        input.compatShellCommand("keyevent", "--duration", "10", "--delay", "20", "--async", "A", "B")
        assertTrue(events[2].eventTime - events[0].eventTime >= 30)
        assertEquals(listOf(29, 29, 30, 30), events.map { it.keyCode })
        assertEquals(listOf(0, 1, 0, 1), events.map { it.action })
    }

    @Test fun pointerScrollIsBackfilled() {
        var calls = 0
        inputFixture(inject = { e, _ ->
            val motion = e as MotionEvent
            assertEquals(MotionEvent.ACTION_SCROLL, motion.action)
            assertEquals(1f, motion.x); assertEquals(2f, motion.y)
            assertEquals(-3f, motion.getAxisValue(MotionEvent.AXIS_VSCROLL))
            calls++; true
        }).compatShellCommand("mouse", "scroll", "1", "2", "--axis", "VSCROLL,-3")
        assertEquals(1, calls)
    }

    @Test fun invalidOrUnknownCommandsDoNotInjectPartialEvents() {
        val input = inputFixture()
        for (args in listOf(arrayOf("tap", "1"), arrayOf("keyevent", "--duration", "bad", "A"),
                arrayOf("vendor-command"), arrayOf("tap", "1", "2", "--vendor"))) {
            assertThrows(IllegalArgumentException::class.java) { input.compatShellCommand(*args) }
        }
    }

    @Test fun commandCapabilitiesAreScopedAndRespectOldParsers() {
        val commands = InputCommands(Binder())
        fun supported(help: String, vararg args: String): Boolean {
            val parser = InputShellCommandCompat(commands, args)
            assertNotNull(parser.parse())
            return parser.supportedBy(help)
        }
        val old = "keyevent [--longpress|--doubletap] <key> ...\nkeycombination <key> <key>\nswipe <x> [duration]"
        assertTrue(supported(old, "keyevent", "--doubletap", "A"))
        assertFalse(supported(old, "keyevent", "--doubletap", "--longpress", "A"))
        assertFalse(supported(old, "keycombination", "-t", "5", "A", "B"))
        assertFalse(supported(old, "keyevent", "--duration", "5", "A"))
        val newer = "keyevent [--duration ms] [--async] <key>\nkeycombination [-t ms] <key> <key>"
        assertTrue(supported(newer, "keyevent", "--duration", "5", "A"))
        assertTrue(supported(newer, "keycombination", "-t", "5", "A", "B"))
        assertFalse(supported(newer, "keyevent", "--delay", "5", "A"))
        assertFalse(supported("tap <x> <y>", "rotaryencoder", "tap", "1", "2"))
        assertTrue(supported("rotaryencoder\ntap <x> <y>", "rotaryencoder", "tap", "1", "2"))
    }

    @Test fun unknownExtensionsRemainUnparsed() {
        val commands = InputCommands(Binder())
        for (args in listOf(arrayOf("vendor-command"), arrayOf("tap", "--vendor", "x"),
                arrayOf("keyevent", "--vendor", "A"))) {
            assertNull(InputShellCommandCompat(commands, args).parse())
        }
    }
}
