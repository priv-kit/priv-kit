package priv.kit.core

import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeExternalStartupBridgeTest {
    @Test
    fun publicBridgeHostCloseIsIdempotent() {
        val host = testHost(FakeProcess(exitCode = 0))

        host.close()
        host.close()
    }

    @Test
    fun bridgeHostCompletesSuccessfulCommand(): Unit = runBlocking {
        val process = FakeProcess(
            stdout = "ready\nmore\n",
            stderr = "warn\n",
            exitCode = 0,
        )
        val host = testHost(process)

        host.use {
            PrivilegeExternalStartup.runThroughBridge(
                commandLine = "start-command",
                bridge = it.toBridge(),
                options = PrivilegeExternalStartupBridgeOptions(sourcePrefix = "test"),
            )
        }

        assertTrue(process.waited.get())
    }

    @Test
    fun bridgeHostPropagatesCommandFailure(): Unit = runBlocking {
        val host = testHost(
            FakeProcess(
                stderr = "nope\n",
                exitCode = 7,
            ),
        )

        val exception = host.use {
            assertThrows(PrivilegeStartupException::class.java) {
                runBlocking {
                    PrivilegeExternalStartup.runThroughBridge(
                        commandLine = "bad-command",
                        bridge = it.toBridge(),
                    )
                }
            }
        }

        assertTrue(exception.message.orEmpty().contains("exited code=7"))
    }

    @Test
    fun bridgeWaitsForResultAfterOutputEof(): Unit = runBlocking {
        val resultSent = AtomicBoolean(false)
        val resultThreadRef = AtomicReference<Thread?>()
        val bridge = PrivilegeExternalStartupBridge { _, _, _, resultReceiver ->
            resultThreadRef.set(
                thread(name = "bridge-delayed-result", isDaemon = true) {
                    Thread.sleep(50L)
                    resultSent.set(true)
                    resultReceiver.send(0, Bundle.EMPTY)
                },
            )
        }

        PrivilegeExternalStartup.runThroughBridge(
            commandLine = "start-command",
            bridge = bridge,
        )

        resultThreadRef.get()?.join(1_000L)
        assertTrue(resultSent.get())
    }

    @Test
    fun bridgeAcceptsNullResultData(): Unit = runBlocking {
        PrivilegeExternalStartup.runThroughBridge(
            commandLine = "start-command",
            bridge = PrivilegeExternalStartupBridge { _, _, _, resultReceiver ->
                resultReceiver.send(0, null)
            },
        )
    }

    @Test
    fun bridgeTimesOutWhenResultAndEofNeverArrive(): Unit = runBlocking {
        val heldDescriptors = mutableListOf<ParcelFileDescriptor>()
        val bridge = PrivilegeExternalStartupBridge { _, stdout, stderr, _ ->
            heldDescriptors += ParcelFileDescriptor.dup(stdout.fileDescriptor)
            heldDescriptors += ParcelFileDescriptor.dup(stderr.fileDescriptor)
        }

        try {
            val exception = assertThrows(PrivilegeStartupException::class.java) {
                runBlocking {
                    PrivilegeExternalStartup.runThroughBridge(
                        commandLine = "start-command",
                        bridge = bridge,
                        options = PrivilegeExternalStartupBridgeOptions(timeoutMillis = 50L),
                    )
                }
            }

            assertTrue(exception.message.orEmpty().contains("timed out after 50ms"))
        } finally {
            heldDescriptors.forEach { descriptor -> descriptor.close() }
        }
    }

    @Test
    fun bridgeHostRejectsConcurrentCommand(): Unit = runBlocking {
        val process = BlockingProcess()
        val host = testHost(process)
        val firstFailure = AtomicReference<Throwable?>()
        val firstThread = thread(name = "first-external-start", isDaemon = true) {
            runCatching {
                runBlocking {
                    PrivilegeExternalStartup.runThroughBridge(
                        commandLine = "first-command",
                        bridge = host.toBridge(),
                        options = PrivilegeExternalStartupBridgeOptions(timeoutMillis = 2_000L),
                    )
                }
            }.onFailure(firstFailure::set)
        }
        assertTrue(process.started.await(1_000L, TimeUnit.MILLISECONDS))

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            runBlocking {
                PrivilegeExternalStartup.runThroughBridge(
                    commandLine = "second-command",
                    bridge = host.toBridge(),
                )
            }
        }

        process.release.countDown()
        firstThread.join(2_000L)
        host.close()
        assertTrue(exception.message.orEmpty().contains("already running"))
        assertNull(firstFailure.get())
    }

    @Test
    fun bridgeTranscriptContinuesWhenLogListenerThrows(): Unit = runBlocking {
        val transcript = StartupTranscript(
            maxCapturedLines = 80,
            startupLogListener = PrivilegeStartupLogListener {
                error("listener failed")
            },
        )

        transcript.append("stdout", "ready")

        assertTrue(transcript.text().contains("[stdout] ready"))
    }

    private fun testHost(process: Process): PrivilegeExternalStartupHost =
        PrivilegeExternalStartupHost(
            options = PrivilegeExternalStartupOptions(timeoutMillis = 1_000L),
            processRunner = PrivilegeExternalStartupProcessRunner { _, _ -> process },
        )

    private fun PrivilegeExternalStartupHost.toBridge(): PrivilegeExternalStartupBridge =
        PrivilegeExternalStartupBridge { commandLine, stdout, stderr, resultReceiver ->
            start(commandLine, stdout, stderr, resultReceiver)
        }

    private open class FakeProcess(
        stdout: String = "",
        stderr: String = "",
        private val exitCode: Int,
    ) : Process() {
        val waited = AtomicBoolean(false)
        private val stdoutStream = stdout.byteInputStream()
        private val stderrStream = stderr.byteInputStream()
        private val stdinStream = ByteArrayOutputStream()

        override fun getOutputStream(): OutputStream = stdinStream

        override fun getInputStream() = stdoutStream

        override fun getErrorStream() = stderrStream

        override fun waitFor(): Int = exitCode

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            waited.set(true)
            return true
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() = Unit
    }

    private class BlockingProcess : FakeProcess(exitCode = 0) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun waitFor(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean {
            started.countDown()
            return release.await(timeout, unit)
        }
    }
}
