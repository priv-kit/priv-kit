package priv.kit.evdev

import android.system.ErrnoException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.evdev.internal.PrivilegeEvdevBackend
import priv.kit.evdev.internal.IPrivilegeInputDeviceController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PrivilegeEvdevTest {
    private open class Backend : PrivilegeEvdevBackend {
        var opens = 0
        var writes = 0
        var queryError: ErrnoException? = null
        var openError: ErrnoException? = null
        var result = intArrayOf(2, 0)
        var events = intArrayOf()
        val closed = mutableListOf<Int>()
        override fun open(path: String, writable: Boolean): Int {
            openError?.let { throw it }
            return ++opens
        }
        override fun name(fd: Int): ByteArray {
            queryError?.let { throw it }
            return "触摸屏".encodeToByteArray()
        }
        override fun bits(fd: Int, type: Int) = intArrayOf(0, 1, 3)
        override fun axis(fd: Int, axis: Int) = intArrayOf(10, 5, 100, 1, 2, 3)
        override fun slots(fd: Int) = intArrayOf(-1, 42)
        override fun write(fd: Int, events: IntArray): IntArray {
            writes++
            this.events = events
            return result
        }
        override fun close(fd: Int) { closed += fd }
    }

    private val events = listOf(PrivilegeEvdevEvent(1, 30, 1), PrivilegeEvdevEvent(0, 0, 0))
    private fun open(backend: Backend, writable: Boolean = false) =
        PrivilegeEvdevController.createLocal(backend).openDevice("/dev/input/event3", writable)

    @Test fun packsOneBatchWithoutAddingOrReplayingEvents() {
        val backend = Backend()
        open(backend, true).use { device ->
            assertTrue(device.writeEvents(events).isComplete)
            assertArrayEquals(intArrayOf(1, 30, 1, 0, 0, 0), backend.events)
            assertEquals(1, backend.writes)
        }
        assertEquals(listOf(1), backend.closed)
    }

    @Test fun partialAndErrnoResultsRetainUncertaintyWithoutRetry() {
        val backend = Backend().apply { result = intArrayOf(1, 0) }
        open(backend, true).use { device ->
            val partial = device.writeEvents(events)
            assertEquals(1, partial.submittedEventCount)
            assertFalse(partial.isComplete)
            backend.result = intArrayOf(-1, 5)
            val failed = device.writeEvents(events)
            assertNull(failed.submittedEventCount)
            assertEquals(5, failed.errno)
            assertEquals(2, backend.writes)
        }
    }

    @Test fun readOnlyAndInvalidBatchesDoNotReachNativeWriter() {
        val backend = Backend()
        open(backend).use { device ->
            assertThrows(IllegalStateException::class.java) { device.writeEvents(events) }
        }
        open(backend, true).use { device ->
            assertThrows(IllegalArgumentException::class.java) { device.writeEvents(emptyList()) }
            assertThrows(IllegalArgumentException::class.java) { device.writeEvents(List(257) { events[0] }) }
        }
        assertEquals(0, backend.writes)
    }

    @Test fun invalidPathIsRejectedBeforeLoadingNativeCode() {
        for (path in listOf("/dev/input/../event3", "event0", "/dev/input/event-1", "/dev/null")) {
            PrivilegeEvdevController.createLocal().use { input ->
                assertThrows(IllegalArgumentException::class.java) { input.openDevice(path) }
            }
        }
    }

    @Test fun closeIsIdempotentAndOldHandleCannotAffectReusedFd() {
        val backend = Backend()
        val input = PrivilegeEvdevController.createLocal(backend)
        val old = input.openDevice("/dev/input/event3")
        old.close()
        backend.opens = 0 // The OS may reuse a descriptor for a new open.
        val next = input.openDevice("/dev/input/event3")
        old.close()
        assertThrows(IllegalStateException::class.java) { old.queryName() }
        assertEquals("触摸屏", next.queryName())
        assertEquals(listOf(1), backend.closed)
        next.close()
        input.close()
        assertEquals(listOf(1, 1), backend.closed)
    }

    @Test fun preservesRawAxisAndTrackingState() {
        open(Backend()).use { device ->
            assertEquals(PrivilegeEvdevAxis(10, 5, 100, 1, 2, 3), device.queryAxis(53))
            assertEquals(listOf(-1, 42), device.queryTrackingIds())
            assertEquals(setOf(0, 1, 3), device.queryCapabilities())
        }
    }

    @Test fun queryFailurePreservesErrnoAndClosesThroughUse() {
        val cause = ErrnoException("ioctl", 19)
        val backend = Backend().apply { queryError = cause }
        val error = assertThrows(PrivilegeEvdevSyscallException::class.java) {
            open(backend).use { it.queryName() }
        }
        assertEquals(19, error.errno)
        assertSame(cause, error.cause)
        assertEquals(listOf(1), backend.closed)
    }

    @Test fun failedOpenDoesNotCreateHandleOrCloseUnownedFd() {
        val cause = ErrnoException("open", 13)
        val backend = Backend().apply { openError = cause }
        val error = assertThrows(PrivilegeEvdevSyscallException::class.java) { open(backend) }
        assertEquals(13, error.errno)
        assertSame(cause, error.cause)
        assertTrue(backend.closed.isEmpty())
    }

    @Test fun closingOneHandleDoesNotCloseOthers() {
        val backend = Backend()
        val input = PrivilegeEvdevController.createLocal(backend)
        val devices = List(20) { input.openDevice("/dev/input/event3") }
        devices.first().close()
        assertEquals("触摸屏", devices.last().queryName())
        devices.forEach { it.close() }
        input.close()
        assertEquals((1..20).toList(), backend.closed)
    }

    @Test fun closeWaitsForInFlightQuery() = verifyCloseWaitsForQuery(false)

    @Test fun binderSessionCloseWaitsForInFlightLocalQuery() = verifyCloseWaitsForQuery(true)

    @Suppress("DEPRECATION") // Thread.id also works on JDK 17 test hosts.
    private fun verifyCloseWaitsForQuery(closeSession: Boolean) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val queryActive = AtomicBoolean()
        val queryThread = AtomicReference<Thread>()
        val closingThread = AtomicReference<Thread>()
        val backend = object : Backend() {
            override fun name(fd: Int): ByteArray {
                queryActive.set(true)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                queryActive.set(false)
                return super.name(fd)
            }
            override fun close(fd: Int) {
                check(!queryActive.get()) { "Descriptor closed during ioctl" }
                super.close(fd)
            }
        }
        val input = PrivilegeEvdevController.createLocal(backend)
        val device = input.openDevice("/dev/input/event3")
        val endpoint = IPrivilegeInputDeviceController.Stub.asInterface(input.asBinder())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val query = executor.submit<String> {
                queryThread.set(Thread.currentThread())
                device.queryName()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val close = executor.submit {
                closingThread.set(Thread.currentThread())
                closing.countDown()
                if (closeSession) {
                    assertNull(endpoint.close().error)
                } else device.close()
            }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            val threads = ManagementFactory.getThreadMXBean()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            var blocked = threads.getThreadInfo(closingThread.get().id)
            while (blocked?.lockOwnerId != queryThread.get().id && !close.isDone && System.nanoTime() < deadline) {
                Thread.sleep(1)
                blocked = threads.getThreadInfo(closingThread.get().id)
            }
            assertEquals("Close must wait for the query's monitor", queryThread.get().id, blocked?.lockOwnerId)
            assertEquals(Thread.State.BLOCKED, blocked?.threadState)
            assertFalse(close.isDone)
            release.countDown()
            assertEquals("触摸屏", query.get(5, TimeUnit.SECONDS))
            close.get(5, TimeUnit.SECONDS)
            assertEquals(listOf(1), backend.closed)
            assertThrows(IllegalStateException::class.java) { device.queryName() }
        } finally {
            release.countDown()
            executor.shutdownNow()
            device.close()
            input.close()
        }
    }
}
