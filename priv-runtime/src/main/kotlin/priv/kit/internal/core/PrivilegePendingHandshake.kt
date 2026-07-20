package priv.kit.internal.core

import priv.kit.PrivilegeStartupException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class PrivilegePendingHandshake {
    private val latch = CountDownLatch(1)

    private val result = AtomicReference<PrivilegeServerHandshakeResult?>(null)

    internal fun complete(result: PrivilegeServerHandshakeResult): Boolean {
        if (!this.result.compareAndSet(null, result)) return false
        latch.countDown()
        return true
    }

    internal fun completedResultOrNull(): PrivilegeServerHandshakeResult? = result.get()

    @Throws(PrivilegeStartupException::class, InterruptedException::class)
    fun await(timeoutMillis: Long): PrivilegeServerHandshakeResult {
        val completed = try {
            latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
        if (!completed) {
            throw PrivilegeStartupException("Timed out waiting for Privileged Server Binder")
        }
        return result.get()
            ?: throw PrivilegeStartupException("Privileged Server handshake completed without a Binder")
    }
}
