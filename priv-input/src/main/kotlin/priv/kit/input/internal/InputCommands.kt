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
package priv.kit.input.internal

import android.hardware.input.IInputManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

/** Validates arguments before local event injection. */
internal class InputCommands(
    private val inputBinder: IBinder,
) {
    private val injector by lazy { InputCommandInjector(IInputManager.Stub.asInterface(inputBinder)) }

    fun shellCommand(args: Array<out String>): String = withClearedCallingIdentity {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { "Input service commands require Android 12 or newer" }
        inputShellCommand(inputBinder, args)
    }

    private val commandHelp by lazy { shellCommand(arrayOf("help")) }

    fun compatShellCommand(args: Array<out String>): String {
        val parsed = InputShellCommandCompat(this, args)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && parsed.supportedBy(commandHelp)) return shellCommand(args)
        val local = parsed.parse()
        if (local == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return shellCommand(args)
        requireNotNull(local) { "Unsupported input command: ${args.joinToString(" ")}" }
        kotlinx.coroutines.runBlocking { local() }
        return ""
    }

    fun keyCode(value: String): Int {
        val code = KeyEvent.keyCodeFromString(value)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && code == KeyEvent.KEYCODE_UNKNOWN)
            KeyEvent.keyCodeFromString("KEYCODE_$value") else code
    }

    suspend fun keyEvent(keyCodes: IntArray, longPress: Boolean, durationMillis: Long, doubleTap: Boolean,
                 async: Boolean, delayMillis: Long, source: Int, displayId: Int) = execute(source, displayId) {
        require(keyCodes.isNotEmpty() && keyCodes.all { it >= 0 }) { "Expected Android key codes" }
        require(!longPress || durationMillis <= 0) { "Cannot combine longPress and durationMillis" }
        injector.keyEvent(keyCodes, longPress, durationMillis, doubleTap, async, delayMillis, source, displayId)
    }

    suspend fun text(text: String, source: Int, displayId: Int) = execute(source, displayId) {
        injector.text(text, source, displayId)
    }

    suspend fun tap(x: Float, y: Float, source: Int, displayId: Int) = execute(source, displayId) {
        coordinates(x, y)
        injector.tap(x, y, resolveInputSource(source, InputDevice.SOURCE_TOUCHSCREEN), displayId)
    }

    suspend fun press(source: Int, displayId: Int) = execute(source, displayId) {
        injector.tap(0f, 0f, resolveInputSource(source, InputDevice.SOURCE_TRACKBALL), displayId)
    }

    suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMillis: Long,
              source: Int, displayId: Int, drag: Boolean) = execute(source, displayId) {
        coordinates(startX, startY, endX, endY)
        require(durationMillis in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Duration must fit a signed 32-bit integer" }
        injector.swipe(startX, startY, endX, endY, durationMillis, source, displayId, drag)
    }

    suspend fun roll(dx: Float, dy: Float, source: Int, displayId: Int) = execute(source, displayId) {
        coordinates(dx, dy)
        injector.roll(dx, dy, source, displayId)
    }

    suspend fun motionEvent(action: Int, x: Float, y: Float, source: Int, displayId: Int) = execute(source, displayId) {
        require(action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL)) {
            "Expected DOWN, UP, MOVE or CANCEL"
        }
        coordinates(x, y)
        injector.motionEvent(action, x, y, source, displayId)
    }

    suspend fun scroll(x: Float, y: Float, axes: Map<Int, Float>, source: Int, displayId: Int) = execute(source, displayId) {
        val resolved = resolveInputSource(source, InputDevice.SOURCE_ROTARY_ENCODER)
        if (isPointerSource(resolved)) coordinates(x, y)
        require(axes.values.all { it.isFinite() }) { "Coordinates and axis values must be finite" }
        require(axes.keys.all { it == MotionEvent.AXIS_SCROLL || it == MotionEvent.AXIS_HSCROLL || it == MotionEvent.AXIS_VSCROLL }) {
            "Expected SCROLL, HSCROLL or VSCROLL axes"
        }
        injector.scroll(x, y, axes, resolved, displayId)
    }

    suspend fun keyCombination(keyCodes: IntArray, durationMillis: Long?, source: Int, displayId: Int) = execute(source, displayId) {
        require(keyCodes.size >= 2 && keyCodes.all { it > 0 }) { "Expected at least two known key codes" }
        require(durationMillis == null || durationMillis in 0..Int.MAX_VALUE.toLong()) { "Duration must be a nonnegative 32-bit integer" }
        injector.keyCombination(keyCodes, durationMillis, source, displayId)
    }

    private suspend inline fun execute(source: Int, displayId: Int, crossinline block: suspend () -> Unit) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || displayId <= Display.DEFAULT_DISPLAY) { "Targeting another display requires Android 10 or newer" }
        require(source == InputDevice.SOURCE_UNKNOWN || source in inputSources.values) { "Source has no input command name: $source" }
        withContext(Dispatchers.IO) {
            coroutineContext.ensureActive()
            block()
        }
    }

    private inline fun <T> withClearedCallingIdentity(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        return try { block() } finally { Binder.restoreCallingIdentity(identity) }
    }

    private fun coordinates(vararg values: Float) { require(values.all { it.isFinite() }) { "Coordinates and axis values must be finite" } }

}

internal val inputSources = mapOf(
    "keyboard" to InputDevice.SOURCE_KEYBOARD, "dpad" to InputDevice.SOURCE_DPAD,
    "gamepad" to InputDevice.SOURCE_GAMEPAD, "touchscreen" to InputDevice.SOURCE_TOUCHSCREEN,
    "mouse" to InputDevice.SOURCE_MOUSE, "stylus" to InputDevice.SOURCE_STYLUS,
    "trackball" to InputDevice.SOURCE_TRACKBALL, "touchpad" to InputDevice.SOURCE_TOUCHPAD,
    "touchnavigation" to InputDevice.SOURCE_TOUCH_NAVIGATION, "joystick" to InputDevice.SOURCE_JOYSTICK,
    "rotaryencoder" to InputDevice.SOURCE_ROTARY_ENCODER,
)
