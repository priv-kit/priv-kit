package priv.kit.evdev

import android.os.Binder
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.SystemInputDevice
import priv.kit.evdev.internal.parseTouchDevices
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrivilegeInputTouchDevicesTest {
    private val dump = """
        Event Hub State:
          Devices:
            3: touch
              Classes: TOUCH | TOUCH_MT
              Path: /dev/input/event4
              Descriptor: abc
            8: keyboard
              Classes: KEYBOARD
              Path: /dev/input/event5
        Input Reader State (Nums of device: 2):
          Device 7: touch
            EventHub Devices: [ 3 ]
            Touch Input Mapper (mode - DIRECT):
              Viewport INTERNAL: displayId=2, uniqueId=local:2
          Device 9: keyboard
            EventHub Devices: [ 8 ]
        Input Dispatcher State:
          displayId=9
    """.trimIndent()
    private val devices = listOf(SystemInputDevice(7, "abc", 2))
    private fun parse(value: String = dump, modern: Boolean = false) =
        parseTouchDevices(value.lineSequence(), devices, modern)

    @Test fun joinsDifferentEventHubAndAndroidIds() {
        assertEquals(listOf(PrivilegeEvdevTouchDeviceInfo("/dev/input/event4", "touch", 7, 2)), parse())
        assertEquals(2, parse(modern = true).single().displayId)
    }
    @Test fun olderDumpJoinsByExactDescriptorAndReadsViewport() {
        val old = dump.replace("    EventHub Devices: [ 3 ]\n", "")
            .replace("TOUCH | TOUCH_MT", "0x00000014").replace("Viewport INTERNAL:", "Viewport:")
        assertEquals(2, parse(old).single().displayId)
    }
    @Test fun missingOrAmbiguousViewportStaysUnknown() {
        assertNull(parse(dump.replace("displayId=2", "displayId=-1")).single().displayId)
        assertNull(parse(dump.replace("displayId=2, uniqueId=local:2", "displayId=2\n      Viewport: displayId=3")).single().displayId)
        assertNull(parse(dump.replace("mode - DIRECT", "mode - POINTER")).single().displayId)
        assertNull(parseTouchDevices(dump.lineSequence(), listOf(SystemInputDevice(7, "abc", null)), true).single().displayId)
    }
    @Test fun modernAssociationMustAgreeWithDirectViewport() {
        assertNull(parseTouchDevices(dump.lineSequence(), listOf(SystemInputDevice(7, "abc", 4)), true).single().displayId)
        assertNull(parse(dump.replace("mode - DIRECT", "mode - POINTER"), true).single().displayId)
    }
    @Test fun mergedNodesRequireEveryTouchMapperToAgree() {
        val merged = dump.replace("EventHub Devices: [ 3 ]", "EventHub Devices: [ 3 8 ]")
            .replace("Classes: KEYBOARD", "Classes: TOUCH")
            .replace("  Device 9: keyboard\n    EventHub Devices: [ 8 ]\n", "")
        val marker = "      Viewport INTERNAL: displayId=2, uniqueId=local:2"
        for (modern in listOf(false, true)) {
            assertTrue(parse(merged, modern).all { it.displayId == null })
            val same = merged.replace(marker, marker + "\n    Touch Input Mapper (mode - DIRECT):\n      Viewport: displayId=2")
            assertEquals(listOf(2, 2), parse(same, modern).map { it.displayId })
            for (suffix in listOf(
                "\n    Touch Input Mapper (mode - DIRECT):\n      Viewport: displayId=3",
                "\n    Touch Input Mapper (mode - DIRECT):\n      Viewport: displayId=-1",
                "\n    Touch Input Mapper (mode - DIRECT):",
                "\n    Touch Input Mapper (mode - POINTER):\n      Viewport: displayId=2",
            )) assertTrue(parse(merged.replace(marker, marker + suffix), modern).all { it.displayId == null })
        }
    }
    @Test fun ambiguousReaderDoesNotFallBackToDescriptor() {
        val ambiguous = dump.replace("EventHub Devices: [ 8 ]", "EventHub Devices: [ 3 ]")
        assertNull(parse(ambiguous, true).single().inputDeviceId)
        assertNull(parse(ambiguous, true).single().displayId)
    }
    @Test fun unknownAssociationDoesNotGuessByNameOrNumber() {
        val unknown = dump.replace("EventHub Devices: [ 3 ]", "EventHub Devices: [ 5 ]").replace("Descriptor: abc", "Descriptor: other")
        val result = parse(unknown).single()
        assertNull(result.inputDeviceId)
        assertNull(result.displayId)
    }
    @Test fun missingDumpSectionsFailInsteadOfReportingNoDevices() {
        assertThrows(IOException::class.java) { parse("Permission Denial") }
        assertTrue(parse("Event Hub State:\nInput Reader State:").isEmpty())
    }
    @Test fun resultCrossesAidlAndClosedControllerRejectsQuery() {
        val expected = parse()
        PrivilegeEvdevController.createLocal(null, touchDevices = { expected }).use { local ->
            val bridge = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
                    local.asBinder().transact(code, data, reply, flags)
            }
            val remote = PrivilegeEvdevController.fromBinder(bridge)
            assertEquals(expected, remote.queryTouchDevices())
            remote.close()
            assertThrows(IllegalStateException::class.java) { remote.queryTouchDevices() }
        }
        PrivilegeEvdevController.createLocal().use {
            assertThrows(IllegalStateException::class.java) { it.queryTouchDevices() }
        }
    }
}
