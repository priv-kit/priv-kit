package priv.kit.sample.command

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import priv.kit.core.Privilege
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.command.PrivilegeCommandEvent
import priv.kit.core.command.PrivilegeCommandProcess
import priv.kit.sample.common.toDiagnosticString

internal class PrivilegeSampleCommandViewModel : ViewModel() {
    var state by mutableStateOf(PrivilegeSampleCommandState())
        private set

    private var operationJob: Job? = null

    fun onServerDisconnected() {
        cancel("Server disconnected")
    }

    fun updateCommand(value: String) {
        if (state.isRunning) return
        state = state.copy(commandText = value)
    }

    fun updateTimeout(value: String) {
        if (state.isRunning) return
        state = state.copy(
            timeoutText = value.filter(Char::isDigit),
            inputError = null,
        )
    }

    fun runStreaming() {
        runCommand(streaming = true)
    }

    fun runForResult() {
        runCommand(streaming = false)
    }

    fun cancel(status: String = "Cancelled") {
        val job = operationJob ?: return
        operationJob = null
        job.cancel()
        state = state.copy(
            isRunning = false,
            status = status,
        )
    }

    fun clearOutput() {
        if (state.isRunning) return
        state = state.copy(
            stdout = "",
            stderr = "",
            stdoutTruncated = false,
            stderrTruncated = false,
            status = "Ready",
        )
    }

    private fun runCommand(streaming: Boolean) {
        if (state.isRunning) return
        val commandText = state.commandText.trim()
        if (commandText.isEmpty()) {
            state = state.copy(inputError = "Enter a shell command.")
            return
        }
        val parsedTimeout = parseTimeout() ?: return
        val timeoutMillis = parsedTimeout.value
        state = state.copy(
            isRunning = true,
            inputError = null,
            stdout = "",
            stderr = "",
            stdoutTruncated = false,
            stderrTruncated = false,
            status = if (streaming) "Starting stream…" else "Waiting for result…",
        )

        operationJob = viewModelScope.launch {
            val runningJob = coroutineContext[Job]
            var process: PrivilegeCommandProcess? = null
            try {
                process = Privilege.startCommand(
                    command = PrivilegeCommand(
                        arguments = listOf("/system/bin/sh", "-c", commandText),
                    ),
                    timeoutMillis = timeoutMillis,
                )
                if (streaming) {
                    collectStreaming(process)
                } else {
                    collectResult(process)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (operationJob === runningJob) {
                    state = state.copy(
                        isRunning = false,
                        status = "Failed: ${throwable.toDiagnosticString()}",
                    )
                }
            } finally {
                process?.close()
                if (operationJob === runningJob) {
                    operationJob = null
                    if (state.isRunning) state = state.copy(isRunning = false)
                }
            }
        }
    }

    private suspend fun collectStreaming(process: PrivilegeCommandProcess) {
        val stdoutDecoder = IncrementalUtf8Decoder()
        val stderrDecoder = IncrementalUtf8Decoder()
        state = state.copy(status = "Streaming")
        process.stream().collect { event ->
            when (event) {
                is PrivilegeCommandEvent.Stdout -> appendStdout(stdoutDecoder.decode(event.bytes))
                is PrivilegeCommandEvent.Stderr -> appendStderr(stderrDecoder.decode(event.bytes))
                is PrivilegeCommandEvent.Exited -> {
                    appendStdout(stdoutDecoder.finish())
                    appendStderr(stderrDecoder.finish())
                    state = state.copy(status = "Exited with code ${event.exitCode}")
                }
            }
        }
    }

    private suspend fun collectResult(process: PrivilegeCommandProcess) {
        val result = process.awaitResult()
        val stdoutText = result.stdout.toString(Charsets.UTF_8)
        val stderrText = result.stderr.toString(Charsets.UTF_8)
        state = state.copy(
            stdout = stdoutText.takeLast(MAX_TRANSCRIPT_CHARS),
            stderr = stderrText.takeLast(MAX_TRANSCRIPT_CHARS),
            stdoutTruncated = result.stdoutTruncated || stdoutText.length > MAX_TRANSCRIPT_CHARS,
            stderrTruncated = result.stderrTruncated || stderrText.length > MAX_TRANSCRIPT_CHARS,
            status = "Exited with code ${result.exitCode}",
        )
    }

    private fun parseTimeout(): ParsedTimeout? {
        val text = state.timeoutText.trim()
        val value = text.toLongOrNull()
        if (value == null || value < 0L) {
            state = state.copy(inputError = "Timeout must be 0 or a positive millisecond value.")
            return null
        }
        return ParsedTimeout(value.takeUnless { it == 0L })
    }

    private fun appendStdout(text: String) {
        if (text.isEmpty()) return
        val appended = appendTranscript(state.stdout, text)
        state = state.copy(
            stdout = appended.text,
            stdoutTruncated = state.stdoutTruncated || appended.truncated,
        )
    }

    private fun appendStderr(text: String) {
        if (text.isEmpty()) return
        val appended = appendTranscript(state.stderr, text)
        state = state.copy(
            stderr = appended.text,
            stderrTruncated = state.stderrTruncated || appended.truncated,
        )
    }
}

internal data class PrivilegeSampleCommandState(
    val commandText: String = DEFAULT_COMMAND,
    val timeoutText: String = "30000",
    val isRunning: Boolean = false,
    val stdout: String = "",
    val stderr: String = "",
    val stdoutTruncated: Boolean = false,
    val stderrTruncated: Boolean = false,
    val status: String = "Ready",
    val inputError: String? = null,
)

private class IncrementalUtf8Decoder {
    private var pending = ByteArray(0)

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val combined = pending + bytes
        val input = ByteBuffer.wrap(combined)
        val output = CharBuffer.allocate(combined.size.coerceAtLeast(1))
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(input, output, false)
        pending = ByteArray(input.remaining()).also(input::get)
        output.flip()
        return output.toString()
    }

    fun finish(): String {
        val remaining = pending
        pending = ByteArray(0)
        return remaining.toString(Charsets.UTF_8)
    }
}

private data class TranscriptAppend(
    val text: String,
    val truncated: Boolean,
)

private data class ParsedTimeout(
    val value: Long?,
)

private fun appendTranscript(current: String, appended: String): TranscriptAppend {
    val combined = current + appended
    return if (combined.length <= MAX_TRANSCRIPT_CHARS) {
        TranscriptAppend(combined, false)
    } else {
        TranscriptAppend(combined.takeLast(MAX_TRANSCRIPT_CHARS), true)
    }
}

private const val MAX_TRANSCRIPT_CHARS: Int = 64 * 1024
private const val DEFAULT_COMMAND: String =
    "for i in 1 2 3 4 5; do echo \"stdout \$i\"; echo \"stderr \$i\" >&2; sleep 1; done"
