package priv.kit.core.internal.command

import android.os.Binder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.command.PrivilegeCommandEvent
import priv.kit.core.command.PrivilegeCommandTimeoutException
import priv.kit.core.command.PrivilegeCommandException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeCommandClientTest {
    @Test
    fun awaitResultDrainsBothStreamsAndReportsTruncation() = runBlocking {
        val stdout = "stdout-data".toByteArray()
        val stderr = "stderr-data".toByteArray()
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { CompletedProcess(stdout, stderr, exitCode = 7) },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = 5_000L,
            )

            val result = process.awaitResult(maxBytesPerStream = 6)

            assertEquals(7, result.exitCode)
            assertArrayEquals("stdout".toByteArray(), result.stdout)
            assertArrayEquals("stderr".toByteArray(), result.stderr)
            assertTrue(result.stdoutTruncated)
            assertTrue(result.stderrTruncated)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun streamReadsStdoutAndStderrConcurrentlyAndEmitsExitLast() = runBlocking {
        val stdout = ByteArray(128 * 1024) { index -> (index % 127).toByte() }
        val stderr = ByteArray(128 * 1024) { index -> (126 - index % 127).toByte() }
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { CompletedProcess(stdout, stderr, exitCode = 3) },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = 5_000L,
            )

            val events = process.stream().toList()
            val receivedStdout = events.filterIsInstance<PrivilegeCommandEvent.Stdout>()
                .flatMap { it.bytes.asIterable() }
                .toByteArray()
            val receivedStderr = events.filterIsInstance<PrivilegeCommandEvent.Stderr>()
                .flatMap { it.bytes.asIterable() }
                .toByteArray()

            assertArrayEquals(stdout, receivedStdout)
            assertArrayEquals(stderr, receivedStderr)
            assertEquals(3, (events.last() as PrivilegeCommandEvent.Exited).exitCode)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun timeoutDestroysProcessAndFailsConsumption() = runBlocking {
        val remoteProcess = BlockingProcess()
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { remoteProcess },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = 50L,
            )

            assertThrows(PrivilegeCommandTimeoutException::class.java) {
                runBlocking { process.awaitResult() }
            }
            assertTrue(remoteProcess.destroyed.get())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun commandOutputCanOnlyBeConsumedOnce(): Unit = runBlocking {
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { CompletedProcess(ByteArray(0), ByteArray(0), exitCode = 0) },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = 5_000L,
            )
            process.awaitResult()

            assertThrows(IllegalStateException::class.java) {
                runBlocking { process.stream().toList() }
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun startFailureDoesNotReturnProcessHandle() = runBlocking {
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { throw IllegalStateException("missing executable") },
        )
        try {
            val exception = assertThrows(PrivilegeCommandException::class.java) {
                runBlocking {
                    PrivilegeCommandClient { executor }.start(
                        command = PrivilegeCommand(listOf("missing")),
                        timeoutMillis = 5_000L,
                    )
                }
            }

            assertTrue(exception.message.orEmpty().contains("missing executable"))
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun cancellingBlockedResultConsumptionDestroysProcessWithoutTimeout(): Unit = runBlocking {
        val remoteProcess = BlockingProcess(blockOutput = true)
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { remoteProcess },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = null,
            )
            val consumer = launch {
                process.awaitResult()
            }
            delay(50L)

            withTimeout(2_000L) {
                consumer.cancelAndJoin()
                while (!remoteProcess.destroyed.get()) delay(10L)
            }

            assertTrue(remoteProcess.destroyed.get())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun explicitProcessCancelUsesCancellationException(): Unit = runBlocking {
        val remoteProcess = BlockingProcess(blockOutput = true)
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = { remoteProcess },
        )
        try {
            val process = PrivilegeCommandClient { executor }.start(
                command = PrivilegeCommand(listOf("test")),
                timeoutMillis = null,
            )
            val consumer = async {
                runCatching { process.awaitResult() }
            }
            delay(50L)

            process.cancel()

            val failure = withTimeout(2_000L) {
                consumer.await().exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
            assertTrue(remoteProcess.destroyed.get())
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun terminalCallbackCanStartReplacementAtConcurrencyLimit() {
        val processIndex = AtomicInteger()
        val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = {
                if (processIndex.incrementAndGet() <= 3) {
                    BlockingProcess()
                } else {
                    CompletedProcess(ByteArray(0), ByteArray(0), exitCode = 0)
                }
            },
        )
        try {
            val initialStarted = CountDownLatch(3)
            repeat(3) { index ->
                startDirect(
                    executor = executor,
                    operationId = "blocking-$index",
                    descriptors = descriptors,
                    receiver = object : ResultReceiver(null) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            if (resultCode == PrivilegeCommandContract.RESULT_STARTED) {
                                initialStarted.countDown()
                            }
                        }
                    },
                )
            }
            assertTrue(initialStarted.await(2, TimeUnit.SECONDS))

            val replacementResult = AtomicInteger(Int.MIN_VALUE)
            val replacementDelivered = CountDownLatch(1)
            val completingReceiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (resultCode != PrivilegeCommandContract.RESULT_EXITED) return
                    startDirect(
                        executor = executor,
                        operationId = "replacement",
                        descriptors = descriptors,
                        receiver = object : ResultReceiver(null) {
                            override fun onReceiveResult(code: Int, data: Bundle?) {
                                if (
                                    code == PrivilegeCommandContract.RESULT_STARTED ||
                                    code == PrivilegeCommandContract.RESULT_FAILED
                                ) {
                                    replacementResult.compareAndSet(Int.MIN_VALUE, code)
                                    replacementDelivered.countDown()
                                }
                            }
                        },
                    )
                }
            }
            startDirect(
                executor = executor,
                operationId = "completing",
                descriptors = descriptors,
                receiver = completingReceiver,
            )

            assertTrue(replacementDelivered.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegeCommandContract.RESULT_STARTED, replacementResult.get())
        } finally {
            executor.shutdown()
            descriptors.forEach { descriptor -> runCatching(descriptor::close) }
        }
    }

    @Test
    fun startAfterShutdownDoesNotCreateProcess() {
        val starts = AtomicInteger()
        val descriptors = CopyOnWriteArrayList<ParcelFileDescriptor>()
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = {
                starts.incrementAndGet()
                CompletedProcess(ByteArray(0), ByteArray(0), exitCode = 0)
            },
        )
        executor.shutdown()
        val result = AtomicInteger(Int.MIN_VALUE)
        val delivered = CountDownLatch(1)

        startDirect(
            executor = executor,
            operationId = "after-shutdown",
            descriptors = descriptors,
            receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    result.set(resultCode)
                    delivered.countDown()
                }
            },
        )

        assertTrue(delivered.await(2, TimeUnit.SECONDS))
        assertEquals(PrivilegeCommandContract.RESULT_FAILED, result.get())
        assertEquals(0, starts.get())
        descriptors.forEach { descriptor -> runCatching(descriptor::close) }
    }

    @Test
    fun missingBinderArgumentsReturnFailure() {
        val executor = PrivilegeCommandExecutorBinder(
            processStarter = {
                throw AssertionError("Malformed requests must not start a process")
            },
        )
        val result = AtomicInteger(Int.MIN_VALUE)
        val delivered = CountDownLatch(1)
        try {
            executor.startCommand(
                null,
                null,
                null,
                null,
                null,
                object : ResultReceiver(null) {
                    override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                        result.set(resultCode)
                        delivered.countDown()
                    }
                },
            )

            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            assertEquals(PrivilegeCommandContract.RESULT_FAILED, result.get())
            executor.cancelCommand(null)
        } finally {
            executor.shutdown()
        }
    }

    private fun startDirect(
        executor: PrivilegeCommandExecutorBinder,
        operationId: String,
        descriptors: CopyOnWriteArrayList<ParcelFileDescriptor>,
        receiver: ResultReceiver,
    ) {
        val stdout = ParcelFileDescriptor.createReliablePipe()
        val stderr = ParcelFileDescriptor.createReliablePipe()
        descriptors += stdout[0]
        descriptors += stderr[0]
        executor.startCommand(
            operationId,
            PrivilegeCommandContract.requestBundle(
                PrivilegeCommand(listOf("test")),
                timeoutMillis = null,
            ),
            Binder(),
            stdout[1],
            stderr[1],
            receiver,
        )
    }

    private class CompletedProcess(
        stdout: ByteArray,
        stderr: ByteArray,
        private val exitCode: Int,
    ) : Process() {
        private val stdin = ByteArrayOutputStream()
        private val stdout = ByteArrayInputStream(stdout)
        private val stderr = ByteArrayInputStream(stderr)

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int = exitCode

        override fun exitValue(): Int = exitCode

        override fun destroy() = Unit
    }

    private class BlockingProcess(
        blockOutput: Boolean = false,
    ) : Process() {
        private val exitLatch = CountDownLatch(1)
        private val alive = AtomicBoolean(true)
        private val stdout = if (blockOutput) BlockingInputStream() else ByteArrayInputStream(ByteArray(0))
        private val stderr = if (blockOutput) BlockingInputStream() else ByteArrayInputStream(ByteArray(0))
        val destroyed = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

        override fun getInputStream(): InputStream = stdout

        override fun getErrorStream(): InputStream = stderr

        override fun waitFor(): Int {
            exitLatch.await(5, TimeUnit.SECONDS)
            return 143
        }

        override fun exitValue(): Int {
            check(!alive.get()) { "Process is still alive" }
            return 143
        }

        override fun isAlive(): Boolean = alive.get()

        override fun destroy() {
            destroyed.set(true)
            alive.set(false)
            exitLatch.countDown()
        }

        override fun destroyForcibly(): Process {
            destroy()
            return this
        }
    }

    private class BlockingInputStream : InputStream() {
        private val closed = CountDownLatch(1)

        override fun read(): Int {
            try {
                closed.await()
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted", exception)
            }
            return -1
        }

        override fun close() {
            closed.countDown()
        }
    }
}
