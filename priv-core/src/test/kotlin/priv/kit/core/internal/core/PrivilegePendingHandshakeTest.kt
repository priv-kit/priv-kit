package priv.kit.core.internal.core

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegePendingHandshakeTest {
    @Test
    fun awaitRestoresInterruptFlagBeforePropagatingInterruption() {
        val pendingHandshake = PrivilegePendingHandshake()

        try {
            Thread.currentThread().interrupt()

            assertThrows(InterruptedException::class.java) {
                pendingHandshake.await(timeoutMillis = 1_000L)
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
