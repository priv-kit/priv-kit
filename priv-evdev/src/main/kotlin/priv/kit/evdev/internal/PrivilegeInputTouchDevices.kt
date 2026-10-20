package priv.kit.evdev.internal

import android.content.Context
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.ServiceManager
import android.view.InputDeviceHidden
import priv.kit.evdev.PrivilegeEvdevTouchDeviceInfo
import android.os.IBinder
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.io.IOException
import java.io.InputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal data class SystemInputDevice(val id: Int, val descriptor: String, val displayId: Int?)

internal fun queryTouchDevices(context: Context): List<PrivilegeEvdevTouchDeviceInfo> {
    val identity = Binder.clearCallingIdentity()
    try {
        val binder = ServiceManager.getService("input") ?: throw IOException("Input service is unavailable")
        val dump = readInputDump(binder)
        val manager = checkNotNull(context.getSystemService(InputManager::class.java))
        val devices = manager.inputDeviceIds.asList().mapNotNull { id ->
            manager.getInputDevice(id)?.let { device ->
                SystemInputDevice(id, device.descriptor,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) (device as InputDeviceHidden).getAssociatedDisplayId()
                        .takeIf { it >= 0 } else null)
            }
        }
        return parseTouchDevices(dump.lineSequence(), devices, Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    } finally {
        Binder.restoreCallingIdentity(identity)
    }
}

/** Drain concurrently: a synchronous dump can exceed the pipe buffer. No filesystem access. */
internal fun readInputDump(binder: IBinder): String {
    val pipe = ParcelFileDescriptor.createPipe()
    return readDumpOutput(ParcelFileDescriptor.AutoCloseInputStream(pipe[0]), pipe[1]) {
        binder.dump(pipe[1].fileDescriptor, emptyArray())
    }
}

internal fun readDumpOutput(input: InputStream, writer: Closeable, dump: () -> Unit): String {
    // Reader failure and caller interruption can race; either may release the read end.
    val closed = AtomicBoolean()
    fun closeInput() { if (closed.compareAndSet(false, true)) input.close() }
    try {
        val output = writer.use {
            FutureTask {
                try { input.bufferedReader().readText() } finally { closeInput() }
            }.also {
                Thread(it, "input-dump-reader").apply { isDaemon = true }.start()
                dump()
            }
        } // Deliver EOF before waiting for the reader.
        return try {
            output.get()
        } catch (error: ExecutionException) {
            throw IOException("Cannot read input dump", error.cause)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while reading input dump", error)
        }
    } finally {
        closeInput()
    }
}

private val hubHeader = Regex("""    (-?\d+): (.*)""")
private val readerHeader = Regex("""  Device (-?\d+): .*""")
private val deviceNumber = Regex("""-?\d+""")
private val viewportDisplay = Regex("""\bdisplayId=(-?\d+)\b""")
private val eventPath = Regex("""/dev/input/event[0-9]+""")

/** Only explicit system links are joined; names and device numbers are never matched heuristically. */
internal fun parseTouchDevices(
    lines: Sequence<String>, devices: List<SystemInputDevice>, useAssociatedDisplay: Boolean,
): List<PrivilegeEvdevTouchDeviceInfo> {
    data class Node(val id: Int, val name: String, var path: String = "", var descriptor: String = "", var touch: Boolean = false)
    data class Reader(val id: Int, val hubs: MutableList<Int> = mutableListOf(),
                      val viewports: MutableList<MutableSet<Int>> = mutableListOf(), var nonDirect: Boolean = false)
    val nodes = mutableListOf<Node>()
    val readers = mutableListOf<Reader>()
    var section = ""
    var node: Node? = null
    var reader: Reader? = null
    var touchMapper = false
    var sawHub = false
    var sawReader = false
    for (line in lines) {
        if (line.isNotEmpty() && !line.first().isWhitespace()) {
            section = line
            node = null
            reader = null
            if (line == "Event Hub State:") sawHub = true
            if (line.startsWith("Input Reader State")) sawReader = true
        }
        if (section == "Event Hub State:") {
            hubHeader.matchEntire(line)?.let {
                node = Node(it.groupValues[1].toInt(), it.groupValues[2]).also(nodes::add)
            }
            val value = line.trim()
            when {
                value.startsWith("Path: ") -> node?.path = value.substringAfter("Path: ")
                value.startsWith("Descriptor: ") -> node?.descriptor = value.substringAfter("Descriptor: ")
                value.startsWith("Classes: ") -> {
                    val classes = value.substringAfter("Classes: ")
                    node?.touch = classes.split(" | ").any { it == "TOUCH" || it == "TOUCH_MT" } ||
                        ((classes.removePrefix("0x").toLongOrNull(16) ?: 0) and 4L != 0L)
                }
            }
        } else if (section.startsWith("Input Reader State")) {
            readerHeader.matchEntire(line)?.let {
                reader = Reader(it.groupValues[1].toInt()).also(readers::add)
                touchMapper = false
            }
            val value = line.trim()
            if (line.startsWith("    ") && !line.startsWith("     ") && "Mapper" in value) {
                touchMapper = value == "Touch Input Mapper (mode - DIRECT):"
                if (value.startsWith("Touch Input Mapper")) {
                    if (touchMapper) reader?.viewports?.add(mutableSetOf()) else reader?.nonDirect = true
                }
            }
            if (value.startsWith("EventHub Devices: [")) {
                deviceNumber.findAll(value.substringAfter('[').substringBefore(']')).forEach {
                    reader?.hubs?.add(it.value.toInt())
                }
            }
            if (touchMapper && value.startsWith("Viewport")) {
                viewportDisplay.find(value)?.groupValues?.get(1)?.toInt()?.let {
                    reader?.viewports?.lastOrNull()?.add(it)
                }
            }
        }
    }
    if (!sawHub || !sawReader) throw IOException("Input dump is unavailable or has an unrecognized format")
    return nodes.filter { it.touch && eventPath.matches(it.path) }.map { node ->
        val byDescriptor = devices.filter { it.descriptor == node.descriptor && node.descriptor.isNotEmpty() }.singleOrNull()
        val candidates = readers.filter { node.id in it.hubs || (it.hubs.isEmpty() && it.id == byDescriptor?.id) }
        val match = candidates.singleOrNull()
        val id = if (candidates.size > 1) null else match?.id ?: byDescriptor?.id
        // A logical device can merge several nodes. Its first associated mapper is not
        // proof that every touch node targets that display. Require complete agreement.
        val touchNodeCount = if (match == null) 0 else nodes.count {
            it.touch && (it.id in match.hubs || (match.hubs.isEmpty() && it.descriptor == byDescriptor?.descriptor))
        }
        val viewport = match?.takeIf { !it.nonDirect && it.viewports.isNotEmpty() && it.viewports.size == touchNodeCount }
            ?.viewports?.map { it.singleOrNull()?.takeIf { id -> id >= 0 } }
            ?.takeIf { ids -> ids.all { it != null } }?.distinct()?.singleOrNull()
        val display = viewport?.takeIf { !useAssociatedDisplay ||
            devices.singleOrNull { device -> device.id == id }?.displayId == it }
        PrivilegeEvdevTouchDeviceInfo(node.path, node.name, id, display)
    }.sortedBy { it.path }
}
