package priv.kit.core.command

import java.io.Closeable
import kotlinx.coroutines.flow.Flow

/**
 * A running non-interactive command.
 *
 * Exactly one output-consumption method may be used: either [stream] or [awaitResult]. Closing
 * an unfinished process cancels it in the privileged server.
 */
public abstract class PrivilegeCommandProcess internal constructor() : Closeable {
    /** Returns a single-collection flow of stdout, stderr, and the final exit event. */
    public abstract fun stream(): Flow<PrivilegeCommandEvent>

    /**
     * Drains stdout and stderr concurrently, then returns their bounded captures and exit code.
     * [maxBytesPerStream] limits each returned byte array; excess bytes are still drained.
     */
    public abstract suspend fun awaitResult(
        maxBytesPerStream: Int = DEFAULT_MAX_BYTES_PER_STREAM,
    ): PrivilegeCommandResult

    /** Cancels the remote process and closes its local output descriptors. */
    public abstract fun cancel()

    final override fun close() {
        cancel()
    }

    public companion object {
        public const val DEFAULT_MAX_BYTES_PER_STREAM: Int = 1024 * 1024
    }
}
