package priv.kit.input.internal

import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent

/** Parses only library-supported syntax. Unrecognized extensions remain device commands. */
internal class InputShellCommandCompat(
    private val input: InputCommands,
    private val args: Array<out String>,
) {
    private var index = 0
    private var command = ""
    private val options = mutableSetOf<String>()
    private var source = InputDevice.SOURCE_UNKNOWN
    private var displayId = Display.INVALID_DISPLAY

    private fun next(): String = args.getOrNull(index++)
        ?: throw IllegalArgumentException("Missing argument for $command")
    private fun number(): Float = next().toFloat()
    private fun remaining() = args.size - index

    fun parse(): (suspend () -> Unit)? {
        if (args.isEmpty()) return null
        inputSources[args[index]]?.let { source = it; index++ }
        if (args.getOrNull(index) == "-d") {
            index++
            displayId = when (val value = next().uppercase(java.util.Locale.ROOT)) {
                "INVALID_DISPLAY" -> Display.INVALID_DISPLAY
                "DEFAULT_DISPLAY" -> Display.DEFAULT_DISPLAY
                else -> normalizeDisplayId(value.toInt())
            }
        }
        command = next()
        val knownOptions = when (command) {
            "keyevent" -> setOf("--longpress", "--doubletap", "--async", "--duration", "--delay")
            "keycombination" -> setOf("-t")
            "scroll" -> setOf("--axis")
            else -> emptySet()
        }
        if (command != "text" && args.drop(index).any {
                it.startsWith("-") && it.toFloatOrNull() == null && it !in knownOptions
            }) return null
        val action: suspend () -> Unit = when (command) {
            "text" -> {
                val text = next()
                suspend { input.text(text, source, displayId) }
            }
            "tap" -> {
                val x = number(); val y = number()
                suspend { input.tap(x, y, source, displayId) }
            }
            "swipe", "draganddrop" -> {
                val x1 = number(); val y1 = number(); val x2 = number(); val y2 = number()
                val duration = if (remaining() > 0) next().toInt().toLong() else DEFAULT_SWIPE_DURATION_MILLIS
                suspend { input.swipe(x1, y1, x2, y2, duration, source, displayId, command == "draganddrop") }
            }
            "press" -> suspend { input.press(source, displayId) }
            "roll" -> {
                val x = number(); val y = number()
                suspend { input.roll(x, y, source, displayId) }
            }
            "keyevent" -> {
                var longPress = false; var doubleTap = false; var async = false
                var duration = 0L; var delay = 0L
                while (args.getOrNull(index)?.startsWith("--") == true) {
                    val option = next()
                    options.add(option)
                    when (option) {
                        "--longpress" -> longPress = true
                        "--doubletap" -> doubleTap = true
                        "--async" -> async = true
                        "--duration" -> duration = next().toLong()
                        "--delay" -> delay = next().toLong()
                        else -> return null
                    }
                }
                if (args.drop(index).any { it.startsWith("--") }) return null
                val keys = args.drop(index).map(input::keyCode).toIntArray()
                index = args.size
                suspend { input.keyEvent(keys, longPress, duration, doubleTap, async, delay, source, displayId) }
            }
            "keycombination" -> {
                val duration = if (args.getOrNull(index) == "-t") {
                    options.add(next()); next().toInt().toLong()
                } else 0L
                if (args.drop(index).any { it.startsWith("-") }) return null
                val keys = args.drop(index).map(input::keyCode).toIntArray()
                index = args.size
                suspend { input.keyCombination(keys, duration, source, displayId) }
            }
            "motionevent" -> {
                val name = next().uppercase(java.util.Locale.ROOT)
                val type = when (name) {
                    "DOWN" -> MotionEvent.ACTION_DOWN
                    "UP" -> MotionEvent.ACTION_UP
                    "MOVE" -> MotionEvent.ACTION_MOVE
                    "CANCEL" -> MotionEvent.ACTION_CANCEL
                    else -> return null
                }
                if (type == MotionEvent.ACTION_CANCEL) options.add("CANCEL")
                val x: Float; val y: Float
                if (type == MotionEvent.ACTION_CANCEL && remaining() == 0) { x = 0f; y = 0f }
                else { x = number(); y = number() }
                suspend { input.motionEvent(type, x, y, source, displayId) }
            }
            "scroll" -> {
                val pointer = isPointerSource(source)
                val x = if (pointer) number() else 0f
                val y = if (pointer) number() else 0f
                val axes = mutableMapOf<Int, Float>()
                while (remaining() > 0) {
                    if (next() != "--axis") return null
                    val pair = next().split(',')
                    require(pair.size == 2) { "Expected --axis NAME,value" }
                    val axis = MotionEvent.axisFromString("AXIS_${pair[0]}")
                    axes[axis] = pair[1].toFloat()
                }
                suspend { input.scroll(x, y, axes, source, displayId) }
            }
            else -> return null
        }
        return if (remaining() == 0) action else null
    }

    fun supportedBy(help: String): Boolean {
        val lines = help.lineSequence().toList()
        if (source != InputDevice.SOURCE_UNKNOWN && lines.none { it.trim() == args[0] }) return false
        val start = lines.indexOfFirst { it.trimStart().startsWith("$command ") }
        if (start == -1) return false
        val description = lines.drop(start + 1).takeWhile { !commandLine.containsMatchIn(it) }.let { listOf(lines[start]) + it }.joinToString("\n")
        // Older keyevent parsers accept only one flag, even when both flags appear in help.
        if (command == "keyevent" && options.size > 1 && "--async" !in description) return false
        return options.all { it in description }
    }
}

private val commandLine = Regex("^\\s*(?:text|keyevent|tap|swipe|draganddrop|press|roll|motionevent|scroll|keycombination)(?:\\s|$)")
