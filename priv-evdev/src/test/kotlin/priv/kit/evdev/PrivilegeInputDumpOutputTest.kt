package priv.kit.evdev

import org.junit.Assert.*
import org.junit.Test
import priv.kit.evdev.internal.readDumpOutput
import java.io.IOException
import java.io.InputStream
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.PipedInputStream
import java.io.PipedOutputStream

class PrivilegeInputDumpOutputTest {
    @Test(timeout = 10000) fun drainsOutputLargerThanPipeCapacityAndClosesReader() {
        val input = PipedInputStream(1024)
        val writer = PipedOutputStream(input)
        val text = "touch 屏幕\n".repeat(200000)
        assertEquals(text, readDumpOutput(input, writer) { writer.write(text.toByteArray()) })
        assertThrows(IOException::class.java) { input.read() }
    }
    @Test(timeout = 10000) fun failedDumpClosesReaderAndKeepsOriginalFailure() {
        val input = PipedInputStream(1024)
        val writer = PipedOutputStream(input)
        val failure = IOException("dump failed")
        assertSame(failure, assertThrows(IOException::class.java) {
            readDumpOutput(input, writer) {
                writer.write(ByteArray(8192)); throw failure
            }
        })
        assertThrows(IOException::class.java) { input.read() }
    }
    @Test(timeout = 10000) fun readerFailureIsReported() {
        val failure = IOException("read failed")
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun close() { closed = true }
        }
        val error = assertThrows(IOException::class.java) { readDumpOutput(input, Closeable {}) {} }
        assertSame(failure, error.cause)
        assertTrue(closed)
    }
    private class WaitingInput : InputStream() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        val reader = AtomicReference<Thread>()
        override fun read(): Int {
            reader.set(Thread.currentThread())
            started.countDown()
            check(closed.await(5, TimeUnit.SECONDS)) { "Reader was not released" }
            return -1
        }
        override fun close() { closes.incrementAndGet(); closed.countDown() }
        fun assertReaderExited() {
            val thread = reader.get()
            assertNotNull(thread)
            thread.join(5000)
            assertFalse("Reader thread leaked", thread.isAlive)
            assertEquals(1, closes.get())
        }
    }
    @Test(timeout = 15000) fun interruptionClosesBothEndsAndReaderExits() {
        val input = WaitingInput()
        val writerClosed = CountDownLatch(1)
        val writerCloses = AtomicInteger()
        val failure = AtomicReference<Throwable>()
        val interruptRestored = AtomicReference<Boolean>()
        val caller = Thread {
            try {
                readDumpOutput(input, Closeable { writerCloses.incrementAndGet(); writerClosed.countDown() }) {
                    check(input.started.await(5, TimeUnit.SECONDS))
                }
            } catch (error: Throwable) {
                failure.set(error)
                interruptRestored.set(Thread.currentThread().isInterrupted)
            }
        }
        caller.start()
        try {
            assertTrue(writerClosed.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(5000)
            assertFalse("Caller did not exit", caller.isAlive)
            assertTrue(failure.get() is IOException)
            assertTrue(failure.get().cause is InterruptedException)
            assertEquals(true, interruptRestored.get())
            input.assertReaderExited()
            assertEquals(1, writerCloses.get())
        } finally {
            input.closed.countDown()
            caller.interrupt()
            caller.join(5000)
        }
    }
    @Test(timeout = 15000) fun failedDumpReleasesBlockedReaderAndClosesEachEndOnce() {
        val input = WaitingInput()
        val writerCloses = AtomicInteger()
        val original = IOException("dump failed")
        try {
            assertSame(original, assertThrows(IOException::class.java) {
                readDumpOutput(input, Closeable { writerCloses.incrementAndGet() }) {
                    check(input.started.await(5, TimeUnit.SECONDS))
                    throw original
                }
            })
            input.assertReaderExited()
            assertEquals(1, writerCloses.get())
        } finally { input.closed.countDown() }
    }
}
