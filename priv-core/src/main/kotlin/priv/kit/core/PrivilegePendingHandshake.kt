package priv.kit.core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PrivilegePendingHandshake internal constructor(
    val token: String,
) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var result: PrivilegeServerHandshakeResult? = null

    internal fun complete(result: PrivilegeServerHandshakeResult) {
        this.result = result
        latch.countDown()
    }

    @Throws(PrivilegeStartupException::class)
    fun await(timeoutMillis: Long): PrivilegeServerHandshakeResult {
        val completed = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!completed) {
            throw PrivilegeStartupException("Timed out waiting for Privileged Server Binder")
        }
        return result ?: throw PrivilegeStartupException("Privileged Server handshake completed without a Binder")
    }
}
