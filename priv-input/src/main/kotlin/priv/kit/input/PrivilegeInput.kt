/*
 * Portions adapted from AOSP Input (Android 8.0, 8.1, 9, 10 and 11).
 * Copyright (C) 2007 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package priv.kit.input

import android.os.IBinder
import android.view.Display
import priv.kit.input.internal.DEFAULT_SWIPE_DURATION_MILLIS
import priv.kit.input.internal.isPointerSource
import android.view.InputDevice
import android.view.MotionEvent
import priv.kit.input.internal.InputCommands

/**
 * Convenience commands inject AOSP-style events and support coroutine cancellation.
 * Cancellation attempts to release active contacts/keys before returning.
 * DeadObjectException propagates without cleanup.
 * Local injection throws [InputInjectionException] when the system returns false.
 * Target displays require Android 10+. The caller coordinates overlapping gestures.
 * Wraps a system input-service Binder forwarded with shell/root identity. Does not own it.
 */
public class PrivilegeInput(binder: IBinder) {
    private val commands = InputCommands(binder)

    /**
     * Runs the device input service's command on Android 12+, returning combined stdout/stderr.
     * Pass tokens, e.g. shellCommand("-d", "0", "tap", "300", "500").
     * Synchronous: use a worker thread. Coroutine cancellation does not stop the device command.
     * Command failures throw [InputShellCommandException]; parameters follow the device's implementation.
     */
    public fun shellCommand(vararg args: String): String = commands.shellCommand(args)

    /**
     * Runs a device command, filling missing commands/options with local event injection.
     * Available on Android 8+. Synchronous; use a worker thread. Local success returns "".
     */
    public fun compatShellCommand(vararg args: String): String = commands.compatShellCommand(args)

    /** Android key codes, in order. async skips dispatch waiting, not hold/delay sleeps. */
    public suspend fun keyEvent(keyCodes: IntArray, longPress: Boolean = false, durationMillis: Long = 0, doubleTap: Boolean = false, async: Boolean = false, delayMillis: Long = 0, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.keyEvent(keyCodes, longPress, durationMillis, doubleTap, async, delayMillis, source, displayId)

    /** Virtual keyboard text; %s becomes a space. Not arbitrary Unicode or clipboard input. */
    public suspend fun text(text: String, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.text(text, source, displayId)

    /** Tap in display pixels. Pointer sources default to display 0; keys default to the focused display. */
    public suspend fun tap(x: Float, y: Float, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.tap(x, y, source, displayId)

    /** Swipe; negative duration selects 300 ms. Android 16+ paces moves at 120 Hz. */
    public suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long = DEFAULT_SWIPE_DURATION_MILLIS, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit = commands.swipe(startX, startY, endX, endY, durationMillis, source, displayId, drag = false)
    /** Swipe after a long press. Android 8–9 excludes the hold from duration; Android 10+ includes it. */
    public suspend fun dragAndDrop(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long = DEFAULT_SWIPE_DURATION_MILLIS, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit = commands.swipe(startX, startY, endX, endY, durationMillis, source, displayId, drag = true)

    /** Only CANCEL may omit coordinates. */
    public suspend fun motionEvent(action: Int, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY) {
        require(action == MotionEvent.ACTION_CANCEL) { "DOWN, UP and MOVE require x and y" }
        motionEvent(action, 0f, 0f, source, displayId)
    }

    /** Non-pointer sources may omit coordinates; pointer sources require x and y. */
    public suspend fun scroll(axes: Map<Int, Float> = emptyMap(), source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY) {
        require(!isPointerSource(source)) { "Pointer scroll requires x and y" }
        scroll(0f, 0f, axes, source, displayId)
    }

    public suspend fun keyEvent(keyCode: Int, longPress: Boolean = false, durationMillis: Long = 0,
                        doubleTap: Boolean = false, async: Boolean = false, delayMillis: Long = 0,
                        source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        keyEvent(intArrayOf(keyCode), longPress, durationMillis, doubleTap, async, delayMillis, source, displayId)

    public suspend fun keyEvent(keyCode: String, longPress: Boolean = false, durationMillis: Long = 0,
                        doubleTap: Boolean = false, async: Boolean = false, delayMillis: Long = 0,
                        source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        keyEvent(intArrayOf(commands.keyCode(keyCode)), longPress, durationMillis, doubleTap, async, delayMillis, source, displayId)

    public suspend fun keyEvent(keyCodes: Array<String>, longPress: Boolean = false, durationMillis: Long = 0,
                        doubleTap: Boolean = false, async: Boolean = false, delayMillis: Long = 0,
                        source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        keyEvent(keyCodes.map(commands::keyCode).toIntArray(), longPress, durationMillis, doubleTap, async, delayMillis, source, displayId)

    public suspend fun keyCombination(keyCodes: Array<String>, durationMillis: Long = 0, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        keyCombination(keyCodes.map(commands::keyCode).toIntArray(), durationMillis, source, displayId)

    /** Trackball press at (0, 0), unless source is specified. */
    public suspend fun press(source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.press(source, displayId)

    /** Zero-pressure relative move, using trackball by default. */
    public suspend fun roll(dx: Float, dy: Float, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.roll(dx, dy, source, displayId)

    /** One DOWN, UP, MOVE or CANCEL event; each call has a new downTime, as in input motionevent. */
    public suspend fun motionEvent(action: Int, x: Float, y: Float, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.motionEvent(action, x, y, source, displayId)

    /** Scroll axes: MotionEvent.AXIS_SCROLL, AXIS_HSCROLL and AXIS_VSCROLL. Default source is rotary encoder. */
    public suspend fun scroll(x: Float, y: Float, axes: Map<Int, Float> = emptyMap(), source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.scroll(x, y, axes, source, displayId)

    /** Ordered chord. Zero duration holds for tap timeout before Android 13, otherwise no hold. */
    public suspend fun keyCombination(keyCodes: IntArray, durationMillis: Long = 0, source: Int = InputDevice.SOURCE_UNKNOWN, displayId: Int = Display.INVALID_DISPLAY): Unit =
        commands.keyCombination(keyCodes, durationMillis, source, displayId)
}
