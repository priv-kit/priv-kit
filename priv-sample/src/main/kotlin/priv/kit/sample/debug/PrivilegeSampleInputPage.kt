package priv.kit.sample.debug

import android.content.Context
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.os.Build
import priv.kit.core.binder.PrivilegeBinderWrapper
import android.view.InputDevice
import android.view.WindowManager
import androidx.compose.runtime.DisposableEffect
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import priv.kit.evdev.PrivilegeEvdevController
import priv.kit.input.PrivilegeInput
import priv.kit.core.Privilege
import priv.kit.core.userservice.PrivilegeUserServiceSpec
import priv.kit.sample.userservice.IPrivilegeSampleInputService
import priv.kit.sample.userservice.PrivilegeSampleInputService
import java.util.UUID
import priv.kit.sample.R

@Composable
internal fun InputTestPage(state: PrivilegeSampleScreenState, callbacks: PrivilegeSampleDebugCallbacks) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var report by remember(state.serverInfo) { mutableStateOf("") }
    val context = LocalContext.current
    val probe = remember(context) { InputProbeView(context) }
    var presentation by remember { mutableStateOf<Presentation?>(null) }
    var target by remember(probe) { mutableStateOf(probe) }
    var targetId by remember { mutableStateOf(0) }
    DisposableEffect(Unit) { onDispose { presentation?.dismiss() } }
    val probeLabel = stringResource(R.string.sample_input_target)
    SamplePageScaffold(
        title = stringResource(R.string.sample_test_input),
        selectedDestination = PrivilegeSampleDebugDestination.Input,
        busy = busy,
        onDestinationSelected = callbacks.destinationSelected,
        onBackToHome = callbacks.backToHome,
        actions = {},
    ) {
        Text(stringResource(R.string.sample_input_description))
        SampleAction(
            label = stringResource(R.string.sample_input_query),
            enabled = state.serverInfo != null && !busy,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = {
                scope.launch {
                    busy = true
                    try {
                        report = withContext(Dispatchers.IO) { inspectInputDevices() }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        report = error.toString()
                    } finally {
                        busy = false
                    }
                }
            },
        )
        SampleAction(
            label = stringResource(R.string.sample_input_display, targetId),
            enabled = !busy,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = {
                val displays = context.getSystemService(DisplayManager::class.java).displays
                val next = displays[(displays.indexOfFirst { it.displayId == targetId } + 1) % displays.size]
                presentation?.dismiss()
                presentation = null
                target = probe
                targetId = 0
                if (next.displayId != 0) {
                    try {
                        val window = Presentation(context, next)
                        val view = InputProbeView(window.context)
                        view.contentDescription = probeLabel
                        window.setContentView(view)
                        window.show()
                        window.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                        presentation = window
                        target = view
                        targetId = next.displayId
                    } catch (error: Exception) { report = error.toString() }
                }
            },
        )
        SampleAction(
            label = stringResource(R.string.sample_input_inject),
            enabled = state.serverInfo != null && !busy,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = {
                val receiver = target
                val displayId = targetId
                val location = IntArray(2)
                receiver.getLocationOnScreen(location)
                val x = location[0] + receiver.width * 0.25f
                val y = location[1] + receiver.height * 0.5f
                val endX = location[0] + receiver.width * 0.75f
                receiver.reset()
                report = ""
                scope.launch {
                    busy = true
                    try {
                        report = withContext(Dispatchers.IO) {
                            val input = PrivilegeInput(
                                checkNotNull(PrivilegeBinderWrapper.fromSystemService(Context.INPUT_SERVICE)),
                            )
                            buildString {
                                suspend fun test(name: String, block: suspend () -> Unit) {
                                    val result = try { block(); "$name: OK" }
                                    catch (error: CancellationException) { throw error }
                                    catch (error: Exception) { "$name: $error" }
                                    appendLine(result)
                                    Log.i("PrivilegeInputProbe", "proxy display=$displayId $result")
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    test("shellCommand help") { check(input.shellCommand("help").contains("tap")) }
                                    test("shellCommand tap") {
                                        input.shellCommand("-d", displayId.toString(), "tap", x.toString(), y.toString())
                                    }
                                }
                                test("compatShellCommand tap") {
                                    input.compatShellCommand("-d", displayId.toString(), "tap", x.toString(), y.toString())
                                }
                                test("compatShellCommand duration") {
                                    input.compatShellCommand("keyevent", "--duration", "50", "A")
                                }
                                test("tap") { input.tap(x, y, displayId = displayId) }
                                test("cancel swipe") {
                                    cancelGesture { input.swipe(x, y, endX, y, 5_000, displayId = displayId) }
                                    input.tap(x, y, displayId = displayId)
                                }
                                test("swipe") { input.swipe(x, y, endX, y, 300, displayId = displayId) }
                                test("draganddrop") { input.dragAndDrop(x, y, endX, y, 800, displayId = displayId) }
                                test("keyevent") { input.keyEvent(arrayOf("A", "B"), displayId = displayId) }
                                test("keyevent options") { input.keyEvent(arrayOf("A", "B"), durationMillis = 80,
                                    doubleTap = true, async = true, delayMillis = 50, displayId = displayId) }
                                test("text") { input.text("a%sb", displayId = displayId) }
                                test("motionevent") {
                                    input.motionEvent(MotionEvent.ACTION_DOWN, x, y, displayId = displayId)
                                    try {
                                        input.motionEvent(MotionEvent.ACTION_MOVE, endX, y, displayId = displayId)
                                    } finally {
                                        withContext(NonCancellable) {
                                            input.motionEvent(MotionEvent.ACTION_UP, endX, y, displayId = displayId)
                                        }
                                    }
                                }
                                test("scroll") { input.scroll(x, y, mapOf(MotionEvent.AXIS_VSCROLL to 2f),
                                    source = InputDevice.SOURCE_MOUSE, displayId = displayId) }
                                test("press") { input.press(displayId = displayId) }
                                test("roll") { input.roll(1f, -1f, displayId = displayId) }
                                test("keycombination") { input.keyCombination(arrayOf("CTRL_LEFT", "A"), durationMillis = 100,
                                    source = InputDevice.SOURCE_KEYBOARD, displayId = displayId) }

                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        report = error.toString()
                    } finally {
                        busy = false
                    }
                }
            },
        )
        SampleAction(
            label = stringResource(R.string.sample_input_evdev),
            enabled = state.serverInfo != null && !busy,
            tone = SampleActionTone.Secondary,
            modifier = Modifier,
            onClick = {
                val receiver = target
                val displayId = targetId
                val location = IntArray(2)
                receiver.getLocationOnScreen(location)
                val x = location[0] + receiver.width * 0.25f
                val endX = location[0] + receiver.width * 0.75f
                val y = location[1] + receiver.height * 0.5f
                receiver.reset()
                scope.launch {
                    busy = true
                    try {
                        report = withContext(Dispatchers.IO) {
                            withInputDevices { devices ->
                                devices.openTouchDevice(displayId).use { device ->
                                    buildString {
                                        device.tap(x, y)
                                        appendLine("evdev tap: OK")
                                        cancelGesture { device.swipe(x, y, endX, y, 5_000) }
                                        appendLine("evdev cancel swipe: OK")
                                        device.tap(x, y)
                                        device.swipe(x, y, endX, y, 300)
                                        appendLine("evdev swipe: OK")
                                        device.dragAndDrop(x, y, endX, y, 300, 600)
                                        appendLine("evdev draganddrop: OK")
                                        Log.i("PrivilegeInputProbe", toString())
                                    }
                                }
                            }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        report = error.toString()
                        Log.e("PrivilegeInputProbe", "evdev test failed", error)
                    } finally { busy = false }
                }
            },
        )
        Text(probeLabel)
        AndroidView(factory = { probe }, modifier = Modifier.fillMaxWidth().height(160.dp),
            update = { it.contentDescription = probeLabel })
        SelectionContainer { Text(listOf(report, target.report).filter { it.isNotEmpty() }.joinToString("\n")) }
    }
}

private suspend fun <T> withInputDevices(block: suspend (PrivilegeEvdevController) -> T): T {
    val connection = Privilege.bindUserService(
        PrivilegeUserServiceSpec(
            serviceClassName = PrivilegeSampleInputService::class.java.name,
            tag = UUID.randomUUID().toString(),
            embedded = false,
            daemon = false,
        ),
    )
    try {
        val service = IPrivilegeSampleInputService.Stub.asInterface(connection.binder)
        return PrivilegeEvdevController.fromBinder(service.deviceController).use { block(it) }
    } finally {
        connection.unbind()
    }
}

private suspend fun inspectInputDevices(): String = withInputDevices { input ->
    buildString {
        input.queryTouchDevices().forEach { device ->
            val entry = "touch $device"
            appendLine(entry)
            Log.i("PrivilegeInputProbe", entry)
        }
        for (path in input.listDevices().take(64)) {
            val description = try {
                input.openDevice(path).use { device ->
                    buildString {
                        appendLine("$path: ${device.queryName()}")
                        val types = device.queryCapabilities()
                        appendLine("types=$types")
                        if (3 in types) device.queryCapabilities(3).forEach { axis ->
                            appendLine("axis[$axis]=${device.queryAxis(axis)}")
                        }
                    }
                }
            } catch (error: Exception) { "$path: $error\n" }
            append(description.take(32_768 - length))
            if (length >= 32_768) break
        }
    }
}

private class InputProbeView(context: Context) : View(context) {
    var report by mutableStateOf("")
        private set
    init {
        isFocusableInTouchMode = true
        setBackgroundColor(0xffdddddd.toInt())
    }
    fun reset() { report = "" }
    private fun record(value: String) {
        val entry = "display=${display?.displayId} $value"
        report = if (report.isEmpty()) entry else "$report\n$entry"
        Log.i("PrivilegeInputProbe", entry)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        record("touch action=${event.action} x=${event.x} y=${event.y} time=${event.eventTime} down=${event.downTime} source=${event.source} tool=${event.getToolType(0)}")
        return true
    }
    override fun onTrackballEvent(event: MotionEvent): Boolean {
        record("trackball action=${event.action} x=${event.x} y=${event.y} source=${event.source}")
        return true
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        record("generic action=${event.action} x=${event.x} y=${event.y} source=${event.source} v=${event.getAxisValue(MotionEvent.AXIS_VSCROLL)} h=${event.getAxisValue(MotionEvent.AXIS_HSCROLL)} scroll=${event.getAxisValue(MotionEvent.AXIS_SCROLL)}")
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CTRL_LEFT)) return super.onKeyDown(keyCode, event)
        record("key down=$keyCode repeat=${event.repeatCount} meta=${event.metaState} source=${event.source}")
        return true
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode !in intArrayOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CTRL_LEFT)) return super.onKeyUp(keyCode, event)
        record("key up=$keyCode meta=${event.metaState} source=${event.source}")
        return true
    }
}

private suspend fun cancelGesture(block: suspend () -> Unit) = coroutineScope {
    val started = SystemClock.uptimeMillis()
    var completed = false
    val gesture = launch { block(); completed = true }
    delay(1_000)
    check(!gesture.isCompleted) { "Gesture ended before cancellation" }
    gesture.cancelAndJoin()
    check(!completed)
    val elapsed = SystemClock.uptimeMillis() - started
    check(elapsed < 3_000) { "Cancellation took $elapsed ms" }
    Log.i("PrivilegeInputProbe", "cancelled 5000ms gesture after $elapsed ms; cleanup complete")
}
