package priv.kit.core

import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import priv.kit.core.internal.external.PrivilegeExternalStartupBridgeHost
import java.io.Closeable

public data class PrivilegeExternalStartupBridgeOptions @JvmOverloads public constructor(
    public val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    public val maxCapturedLines: Int = DEFAULT_MAX_CAPTURED_LINES,
    public val sourcePrefix: String? = null,
) {
    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
        require(maxCapturedLines > 0) { "maxCapturedLines must be positive" }
    }

    internal companion object {
        internal const val DEFAULT_TIMEOUT_MILLIS: Long = 10_000L
        internal const val DEFAULT_MAX_CAPTURED_LINES: Int = 80
    }
}

/**
 * Dispatches a start request through an app-owned Binder bridge.
 *
 * Implementations should return promptly after forwarding the request. The descriptors are valid
 * for the duration of this call; a remote endpoint must retain its own Binder-delivered copies.
 */
public fun interface PrivilegeExternalStartupBridge {
    @Throws(Exception::class)
    public fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    )
}

/**
 * Privileged-process endpoint for an app-owned external-startup bridge.
 *
 * [start] duplicates the endpoint-side descriptors and returns before command execution finishes.
 * The owning app remains responsible for restricting access to the Binder that exposes this host.
 */
public class PrivilegeExternalStartupHost private constructor(
    private val delegate: PrivilegeExternalStartupBridgeHost,
) : Closeable {
    @JvmOverloads
    public constructor(
        options: PrivilegeExternalStartupOptions = PrivilegeExternalStartupOptions(),
    ) : this(
        PrivilegeExternalStartupBridgeHost(
            options = options,
            processRunner = PrivilegeExternalStartupProcessRunner(),
        ),
    )

    internal constructor(
        options: PrivilegeExternalStartupOptions,
        processRunner: PrivilegeExternalStartupProcessRunner,
    ) : this(
        PrivilegeExternalStartupBridgeHost(
            options = options,
            processRunner = processRunner,
        ),
    )

    public fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        delegate.start(
            commandLine = commandLine,
            stdout = stdout,
            stderr = stderr,
            resultReceiver = resultReceiver,
        )
    }

    override fun close() {
        delegate.close()
    }
}
