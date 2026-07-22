package priv.kit.core.internal.core

import priv.kit.core.PrivilegeStartupException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

internal class PrivilegePendingHandshake {
    private val result = AtomicReference<PrivilegeServerHandshakeResult?>(null)
    private val completion = CompletableDeferred<PrivilegeServerHandshakeResult>()

    internal fun complete(result: PrivilegeServerHandshakeResult): Boolean {
        if (!this.result.compareAndSet(null, result)) return false
        completion.complete(result)
        return true
    }

    internal fun completedResultOrNull(): PrivilegeServerHandshakeResult? = result.get()

    suspend fun await(timeoutMillis: Long): PrivilegeServerHandshakeResult =
        withTimeoutOrNull(timeoutMillis.milliseconds) { completion.await() }
            ?: throw PrivilegeStartupException("Timed out waiting for Privileged Server Binder")
}
