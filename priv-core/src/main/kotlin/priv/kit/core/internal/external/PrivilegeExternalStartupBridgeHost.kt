package priv.kit.core.internal.external

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import java.io.BufferedWriter
import java.io.Closeable
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import priv.kit.core.PrivilegeExternalStartupOptions
import priv.kit.core.PrivilegeExternalStartupProcessRunner
import priv.kit.core.PrivilegeStartupLogLine
import priv.kit.core.PrivilegeStartupLogListener
import priv.kit.shared.toPrivilegeDiagnosticString

internal class PrivilegeExternalStartupBridgeHost(
    private val options: PrivilegeExternalStartupOptions,
    private val processRunner: PrivilegeExternalStartupProcessRunner,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val command = Mutex()
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("priv-kit-external-bridge-host"),
    )

    fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        if (closed.get()) {
            rejectClosed(stdout, stderr, resultReceiver)
            return
        }
        if (!command.tryLock()) {
            reject(
                stdout,
                stderr,
                resultReceiver,
                ExternalStartupBridgeProtocol.RESULT_BUSY,
                IllegalStateException("External startup command is already running"),
            )
            return
        }
        if (closed.get()) {
            command.unlock()
            rejectClosed(stdout, stderr, resultReceiver)
            return
        }

        val output = try {
            duplicateOutput(stdout, stderr)
        } catch (throwable: Throwable) {
            command.unlock()
            reject(
                stdout,
                stderr,
                resultReceiver,
                ExternalStartupBridgeProtocol.RESULT_FAILURE,
                throwable,
            )
            return
        }
        runCatching(stdout::close)
        runCatching(stderr::close)

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var resultCode = ExternalStartupBridgeProtocol.RESULT_SUCCESS
            var resultData = Bundle.EMPTY
            var failure: Throwable? = null
            try {
                yield()
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
                failure?.let(output::writeFailure)
                output.close()
                command.unlock()
                sendResult(resultReceiver, resultCode, resultData)
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) scope.cancel()
    }

    private fun duplicateOutput(
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
    ): ExternalStartupBridgeHostOutput {
        val ownedStdout = ParcelFileDescriptor.dup(stdout.fileDescriptor)
        val ownedStderr = try {
            ParcelFileDescriptor.dup(stderr.fileDescriptor)
        } catch (throwable: Throwable) {
            runCatching(ownedStdout::close)
            throw throwable
        }
        return try {
            ExternalStartupBridgeHostOutput(ownedStdout, ownedStderr)
        } catch (throwable: Throwable) {
            runCatching(ownedStdout::close)
            runCatching(ownedStderr::close)
            throw throwable
        }
    }

    private fun rejectClosed(
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) = reject(
        stdout,
        stderr,
        resultReceiver,
        ExternalStartupBridgeProtocol.RESULT_CLOSED,
        IllegalStateException("External startup host is closed"),
    )

    private fun reject(
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
        resultCode: Int,
        failure: Throwable,
    ) {
        try {
            runCatching {
                stderr.toWriter().use { writer ->
                    writer.writeLine(failure.toPrivilegeDiagnosticString())
                }
            }
        } finally {
            runCatching(stdout::close)
            runCatching(stderr::close)
        }
        sendResult(resultReceiver, resultCode, failure.toResultData())
    }

    private fun sendResult(
        resultReceiver: ResultReceiver,
        resultCode: Int,
        resultData: Bundle,
    ) {
        runCatching { resultReceiver.send(resultCode, resultData) }
    }
}

private class ExternalStartupBridgeHostOutput(
    stdout: ParcelFileDescriptor,
    stderr: ParcelFileDescriptor,
) : Closeable {
    private val stdoutWriter = stdout.toWriter()
    private val stderrWriter = stderr.toWriter()

    fun write(line: PrivilegeStartupLogLine) {
        (if (line.source == STDOUT_SOURCE) stdoutWriter else stderrWriter)
            .writeLine(line.message)
    }

    fun writeFailure(throwable: Throwable) {
        runCatching { stderrWriter.writeLine(throwable.toPrivilegeDiagnosticString()) }
    }

    override fun close() {
        runCatching(stdoutWriter::close)
        runCatching(stderrWriter::close)
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

private fun Throwable.toResultData(): Bundle = Bundle().apply {
    putString(
        ExternalStartupBridgeProtocol.RESULT_MESSAGE_KEY,
        message
            ?.lineSequence()
            ?.firstOrNull(String::isNotBlank)
            ?.take(MAX_RESULT_MESSAGE_CHARS)
            ?: javaClass.name,
    )
}

private const val STDOUT_SOURCE = "stdout"
private const val MAX_RESULT_MESSAGE_CHARS = 512
