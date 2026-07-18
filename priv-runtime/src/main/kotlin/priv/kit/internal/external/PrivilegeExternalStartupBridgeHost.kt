package priv.kit.internal.external

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import priv.kit.PrivilegeExternalStartupOptions
import priv.kit.PrivilegeExternalStartupProcessRunner
import priv.kit.PrivilegeStartupLogLine
import priv.kit.PrivilegeStartupLogListener
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

internal class PrivilegeExternalStartupBridgeHost(
    private val options: PrivilegeExternalStartupOptions,
    private val processRunner: PrivilegeExternalStartupProcessRunner,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val activeThread = AtomicReference<Thread?>()

    fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        if (closed.get()) {
            reject(
                stdout = stdout,
                stderr = stderr,
                resultReceiver = resultReceiver,
                resultCode = ExternalStartupBridgeProtocol.RESULT_CLOSED,
                failure = IllegalStateException("External startup host is closed"),
            )
            return
        }
        if (!running.compareAndSet(false, true)) {
            reject(
                stdout = stdout,
                stderr = stderr,
                resultReceiver = resultReceiver,
                resultCode = ExternalStartupBridgeProtocol.RESULT_BUSY,
                failure = IllegalStateException("External startup command is already running"),
            )
            return
        }
        if (closed.get()) {
            running.set(false)
            reject(
                stdout = stdout,
                stderr = stderr,
                resultReceiver = resultReceiver,
                resultCode = ExternalStartupBridgeProtocol.RESULT_CLOSED,
                failure = IllegalStateException("External startup host is closed"),
            )
            return
        }

        val output = try {
            duplicateOutput(stdout, stderr)
        } catch (throwable: Throwable) {
            running.set(false)
            reject(
                stdout = stdout,
                stderr = stderr,
                resultReceiver = resultReceiver,
                resultCode = ExternalStartupBridgeProtocol.RESULT_FAILURE,
                failure = throwable,
            )
            return
        }
        runCatching { stdout.close() }
        runCatching { stderr.close() }

        val worker = thread(
            start = false,
            name = "priv-kit-external-bridge-host",
            isDaemon = true,
        ) {
            var resultCode = ExternalStartupBridgeProtocol.RESULT_SUCCESS
            var resultData = Bundle.EMPTY
            var failure: Throwable? = null
            try {
                processRunner.run(
                    commandLine = commandLine,
                    options = options,
                    startupLogListener = PrivilegeStartupLogListener(output::write),
                )
            } catch (throwable: Throwable) {
                resultCode = ExternalStartupBridgeProtocol.RESULT_FAILURE
                resultData = throwable.toResultData()
                failure = throwable
            } finally {
                if (failure != null) {
                    output.writeFailure(failure)
                }
                output.close()
                activeThread.compareAndSet(Thread.currentThread(), null)
                running.set(false)
                sendResult(resultReceiver, resultCode, resultData)
            }
        }
        activeThread.set(worker)
        try {
            worker.start()
            if (closed.get()) {
                worker.interrupt()
            }
        } catch (throwable: Throwable) {
            activeThread.compareAndSet(worker, null)
            running.set(false)
            output.writeFailure(throwable)
            output.close()
            sendResult(
                resultReceiver = resultReceiver,
                resultCode = ExternalStartupBridgeProtocol.RESULT_FAILURE,
                resultData = throwable.toResultData(),
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeThread.get()?.interrupt()
    }

    private fun duplicateOutput(
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
    ): ExternalStartupBridgeHostOutput {
        val ownedStdout = ParcelFileDescriptor.dup(stdout.fileDescriptor)
        val ownedStderr = try {
            ParcelFileDescriptor.dup(stderr.fileDescriptor)
        } catch (throwable: Throwable) {
            runCatching { ownedStdout.close() }
            throw throwable
        }
        return ExternalStartupBridgeHostOutput(
            stdout = ownedStdout,
            stderr = ownedStderr,
        )
    }

    private fun reject(
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
        resultCode: Int,
        failure: Throwable,
    ) {
        val output = ExternalStartupBridgeHostOutput(stdout, stderr)
        output.writeFailure(failure)
        output.close()
        sendResult(
            resultReceiver = resultReceiver,
            resultCode = resultCode,
            resultData = failure.toResultData(),
        )
    }

    private fun sendResult(
        resultReceiver: ResultReceiver,
        resultCode: Int,
        resultData: Bundle,
    ) {
        runCatching {
            resultReceiver.send(resultCode, resultData)
        }
    }
}

private class ExternalStartupBridgeHostOutput(
    stdout: ParcelFileDescriptor,
    stderr: ParcelFileDescriptor,
) : Closeable {
    private val stdoutWriter = stdout.toWriter()
    private val stderrWriter = stderr.toWriter()

    fun write(line: PrivilegeStartupLogLine) {
        val writer = if (line.source == STDOUT_SOURCE) stdoutWriter else stderrWriter
        writer.writeLine(line.message)
    }

    fun writeFailure(throwable: Throwable) {
        runCatching {
            stderrWriter.writeLine(throwable.toDiagnosticString())
        }
    }

    override fun close() {
        runCatching { stdoutWriter.close() }
        runCatching { stderrWriter.close() }
    }
}

private fun ParcelFileDescriptor.toWriter(): BufferedWriter =
    OutputStreamWriter(
        ParcelFileDescriptor.AutoCloseOutputStream(this),
        Charsets.UTF_8,
    ).buffered()

private fun BufferedWriter.writeLine(message: String) {
    synchronized(this) {
        append(message)
        newLine()
        flush()
    }
}

private fun Throwable.toResultData(): Bundle =
    Bundle().apply {
        putString(
            ExternalStartupBridgeProtocol.RESULT_MESSAGE_KEY,
            message
                ?.lineSequence()
                ?.firstOrNull { it.isNotBlank() }
                ?.take(MAX_RESULT_MESSAGE_CHARS)
                ?: javaClass.name,
        )
    }

private fun Throwable.toDiagnosticString(): String {
    val lines = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        lines += "Cause[$depth]: ${current.javaClass.name}: ${current.message.orEmpty()}"
        current.stackTrace.take(MAX_STACK_FRAMES).forEach { frame ->
            lines += "  at $frame"
        }
        current = current.cause
        depth++
    }
    return lines.joinToString("\n")
}

private const val STDOUT_SOURCE = "stdout"
private const val MAX_RESULT_MESSAGE_CHARS = 512
private const val MAX_CAUSE_DEPTH = 8
private const val MAX_STACK_FRAMES = 8
