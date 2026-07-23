package priv.kit.core.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeAdbStartupCoordinatorTest {
    @Test
    fun adbKeyAuthorizationFailureIsNotRetried() {
        assertFalse(
            shouldRetryAdbConnectFailure(
                throwable = PrivilegeAdbException("ADB key is not authorized"),
                attempt = 1,
                retryCount = 5,
            ),
        )
    }

    @Test
    fun interruptedAdbOperationIsNeverRetriedOrWrapped() {
        val interrupted = InterruptedException("cancelled")
        try {
            assertFalse(
                shouldRetryAdbConnectFailure(
                    throwable = interrupted,
                    attempt = 1,
                    retryCount = 5,
                ),
            )

            val thrown = assertThrows(InterruptedException::class.java) {
                interrupted.rethrowIfInterrupted()
            }
            assertSame(interrupted, thrown)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
