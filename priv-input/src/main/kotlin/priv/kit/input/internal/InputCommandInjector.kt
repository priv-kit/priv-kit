/*
 * Portions adapted from AOSP Input and InputShellCommand (Android 8–17).
 * Copyright (C) 2007 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package priv.kit.input.internal

import android.hardware.input.IInputManager
import android.os.SystemClock
import kotlinx.coroutines.delay
import android.os.DeadObjectException
import android.os.Binder
import android.os.Build
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import android.view.InputDevice
import android.view.InputEvent
import android.view.InputEventHidden
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfigurationHidden
import android.view.ViewConfiguration
import priv.kit.input.InputInjectionException

/** Version-specific AOSP event sequences; newer options use their first supporting implementation. */
internal class InputCommandInjector(
    private val manager: IInputManager,
) {
    suspend fun keyEvent(keyCodes: IntArray, longPress: Boolean, durationMillis: Long, doubleTap: Boolean,
                 async: Boolean, delayMillis: Long, source: Int, displayId: Int) {
        val resolved = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) resolveInputSource(source, InputDevice.SOURCE_KEYBOARD) else source
        // Preserve the immediate repeat used by Android 8–12 longpress calls.
        val legacy = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU &&
            durationMillis <= 0 && !doubleTap && !async && delayMillis <= 0
        val duration = if (longPress) ViewConfiguration.getLongPressTimeout().toLong() else maxOf(0, durationMillis)
        for ((index, code) in keyCodes.withIndex()) {
            if (index > 0 && delayMillis > 0) delay(delayMillis)
            sendKey(code, resolved, displayId, duration, async, legacy && longPress)
            if (doubleTap) {
                delay(ViewConfigurationHidden.getDoubleTapMinTime().toLong())
                sendKey(code, resolved, displayId, duration, async, false)
            }
        }
    }

    suspend fun text(text: String, source: Int, displayId: Int) {
        val buffer = StringBuilder(text)
        var escaped = false
        var index = 0
        while (index < buffer.length) {
            if (escaped) {
                escaped = false
                if (buffer[index] == 's') { buffer.setCharAt(index, ' '); buffer.deleteCharAt(--index) }
            }
            if (buffer[index] == '%') escaped = true
            index++
        }
        val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(buffer.toString().toCharArray())
            ?: throw IllegalArgumentException("Text cannot be represented by the virtual keyboard")
        val pressed = linkedMapOf<Int, KeyEvent>()
        cleanupOnFailure({
            var failure: Exception? = null
            for (event in pressed.values) {
                try { send(KeyEvent.changeAction(event, KeyEvent.ACTION_UP)) }
                catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
            }
            failure?.let { throw it }
        }) {
            for (event in events) {
                event.source = resolveInputSource(source, InputDevice.SOURCE_KEYBOARD)
                display(event, displayId)
                send(event)
                if (event.action == KeyEvent.ACTION_DOWN) pressed[event.keyCode] = event
                else if (event.action == KeyEvent.ACTION_UP) pressed.remove(event.keyCode)
            }
        }
    }

    suspend fun keyCombination(keyCodes: IntArray, durationMillis: Long, source: Int, displayId: Int) {
        // Android 12's default chord holds for tap timeout and does not synthesize modifiers.
        val legacy = durationMillis == 0L && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val now = SystemClock.uptimeMillis()
        var meta = 0
        val pressed = ArrayDeque<Int>()
        fun event(code: Int, action: Int) = KeyEvent(now, now, action, code, 0, meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source).also { display(it, displayId) }
        suspend fun release() {
            var failure: Exception? = null
            while (pressed.isNotEmpty()) {
                val code = pressed.removeFirst()
                try { send(event(code, KeyEvent.ACTION_UP), INJECT_INPUT_EVENT_MODE_ASYNC) } catch (error: Exception) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
                if (!legacy) meta = meta and modifier(code).inv()
            }
            failure?.let { throw it }
        }
        cleanupOnFailure(::release) {
            for (code in keyCodes) {
                send(event(code, KeyEvent.ACTION_DOWN), INJECT_INPUT_EVENT_MODE_ASYNC)
                pressed.addLast(code)
                if (!legacy) meta = meta or modifier(code)
            }
            delay(if (legacy) ViewConfiguration.getTapTimeout().toLong() else durationMillis)
            coroutineContext.ensureActive()
            withContext(NonCancellable) { release() }
        }
    }

    @Suppress("DEPRECATION") // Android 12 uses this timeout for the immediate repeat timestamp.
    private suspend fun sendKey(code: Int, source: Int, displayId: Int, duration: Long, async: Boolean,
                        immediateLongPress: Boolean) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source)
        display(event, displayId)
        val mode = if (async) INJECT_INPUT_EVENT_MODE_ASYNC else INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH
        suspend fun up() {
            val up = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA)
                KeyEvent.changeTimeRepeat(event, SystemClock.uptimeMillis(), 0) else event
            send(KeyEvent.changeAction(up, KeyEvent.ACTION_UP), mode)
        }
        send(event, mode)
        cleanupOnFailure(::up) {
            if (immediateLongPress) {
                val repeatTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    now + ViewConfiguration.getGlobalActionKeyTimeout() else now
                send(KeyEvent.changeTimeRepeat(event, repeatTime, 1, KeyEvent.FLAG_LONG_PRESS), mode)
            } else {
                val timeout = ViewConfiguration.getLongPressTimeout().toLong()
                val first = minOf(duration, timeout)
                if (first > 0) {
                    delay(first)
                    if (duration >= timeout) {
                        send(KeyEvent.changeTimeRepeat(event, now + timeout, 1, KeyEvent.FLAG_LONG_PRESS), mode)
                        if (duration > first) delay(duration - first)
                    }
                }
            }
            up()
        }
    }

    private fun modifier(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON or KeyEvent.META_CTRL_ON
        KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON
        KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON or KeyEvent.META_ALT_ON
        KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON or KeyEvent.META_SHIFT_ON
        KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_LEFT_ON or KeyEvent.META_META_ON
        KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_RIGHT_ON or KeyEvent.META_META_ON
        else -> 0
    }

    suspend fun tap(x: Float, y: Float, source: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        motion(source, displayId, MotionEvent.ACTION_DOWN, now, now, x, y, 1f)
        cleanupOnFailure({ motion(source, displayId, MotionEvent.ACTION_CANCEL, now, SystemClock.uptimeMillis(), x, y, 0f) }) {
            motion(source, displayId, MotionEvent.ACTION_UP, now, now, x, y, 0f)
        }
    }

    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long,
              source: Int, displayId: Int, drag: Boolean) {
        val duration = if (durationMillis < 0) DEFAULT_SWIPE_DURATION_MILLIS else durationMillis
        val resolved = resolveInputSource(source, InputDevice.SOURCE_TOUCHSCREEN)
        val down = SystemClock.uptimeMillis()
        var x = startX
        var y = startY
        motion(resolved, displayId, MotionEvent.ACTION_DOWN, down, down, x, y, 1f)
        var now = down
        cleanupOnFailure({ motion(resolved, displayId, MotionEvent.ACTION_CANCEL, down, SystemClock.uptimeMillis(), x, y, 0f) }) {
            if (drag) delay(ViewConfiguration.getLongPressTimeout().toLong())
            // Android 8-9 starts drag movement timing after the hold and uses each event's
            // own timestamp as downTime. Android 10+ measures from the initial DOWN.
            if (drag || Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) now = SystemClock.uptimeMillis()
            val start = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) now else down
            var injected = 1
            while (now - start < duration) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                    val wait = kotlin.math.floor(injected * (1000f / SWIPE_EVENTS_PER_SECOND) - (now - start)).toLong()
                    if (wait > 0) {
                        if (wait > duration - (now - start)) {
                            delay(duration - (now - start))
                            break
                        }
                        delay(wait)
                    }
                    now = SystemClock.uptimeMillis()
                }
                val alpha = (now - start).toFloat() / duration
                x = (endX - startX) * alpha + startX
                y = (endY - startY) * alpha + startY
                motion(resolved, displayId, MotionEvent.ACTION_MOVE, down, now, x, y, 1f)
                injected++
                now = SystemClock.uptimeMillis()
            }
            motion(resolved, displayId, MotionEvent.ACTION_UP, down, now, endX, endY, 0f)
        }
    }

    suspend fun roll(dx: Float, dy: Float, source: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        motion(resolveInputSource(source, InputDevice.SOURCE_TRACKBALL), displayId, MotionEvent.ACTION_MOVE, now, now, dx, dy, 0f)
    }

    suspend fun motionEvent(action: Int, x: Float, y: Float, source: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        motion(resolveInputSource(source, InputDevice.SOURCE_TOUCHSCREEN), displayId, action, now, now, x, y,
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) 1f else 0f)
    }

    suspend fun scroll(x: Float, y: Float, axes: Map<Int, Float>, source: Int, displayId: Int) {
        val pointer = isPointerSource(source)
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = toolType(source)
        }
        val coords = MotionEvent.PointerCoords().apply {
            size = 1f
            if (pointer) { this.x = x; this.y = y }
            axes.forEach { (axis, value) -> setAxisValue(axis, value) }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(properties),
            arrayOf(coords), 0, 0, 1f, 1f, deviceId(source), 0, source, 0)
        try {
            display(event, motionDisplayId(source, displayId))
            send(event)
        } finally { event.recycle() }
    }

    private suspend fun motion(source: Int, displayId: Int, action: Int, down: Long, time: Long, x: Float, y: Float,
                       pressure: Float) {
        val event = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = toolType(source) }
            val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure; size = 1f }
            MotionEvent.obtain(down, time, action, 1, arrayOf(properties), arrayOf(coords),
                0, 0, 1f, 1f, deviceId(source), 0, source, 0)
        } else MotionEvent.obtain(if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) time else down,
            time, action, x, y, pressure, 1f, 0, 1f, 1f, deviceId(source), 0).apply { this.source = source }
        try {
            display(event, motionDisplayId(source, displayId))
            send(event)
        } finally { event.recycle() }
    }

    private fun toolType(source: Int): Int = when (source) {
        InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_MOUSE_RELATIVE, InputDevice.SOURCE_TRACKBALL -> MotionEvent.TOOL_TYPE_MOUSE
        InputDevice.SOURCE_STYLUS, InputDevice.SOURCE_BLUETOOTH_STYLUS -> MotionEvent.TOOL_TYPE_STYLUS
        InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_TOUCHPAD, InputDevice.SOURCE_TOUCH_NAVIGATION -> MotionEvent.TOOL_TYPE_FINGER
        else -> MotionEvent.TOOL_TYPE_UNKNOWN
    }

    private fun deviceId(source: Int): Int = InputDevice.getDeviceIds().firstOrNull {
        InputDevice.getDevice(it)?.supportsSource(source) == true
    } ?: 0


    private fun display(event: InputEvent, displayId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) (event as InputEventHidden).setDisplayId(normalizeDisplayId(displayId))
    }

    private suspend fun send(event: InputEvent, mode: Int = INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH) {
        coroutineContext.ensureActive()
        val identity = Binder.clearCallingIdentity()
        try {
            if (!manager.injectInputEvent(event, mode)) throw InputInjectionException()
        } finally { Binder.restoreCallingIdentity(identity) }
    }

    private suspend inline fun cleanupOnFailure(crossinline cleanup: suspend () -> Unit, block: suspend () -> Unit) {
        try { block() } catch (failure: Exception) {
            if (failure is DeadObjectException) throw failure
            try { withContext(NonCancellable) { cleanup() } }
            catch (cleanupFailure: Exception) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
    }
}

private const val SWIPE_EVENTS_PER_SECOND = 120
