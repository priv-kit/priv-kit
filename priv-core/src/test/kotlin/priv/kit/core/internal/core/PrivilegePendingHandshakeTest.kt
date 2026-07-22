package priv.kit.core.internal.core

import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import priv.kit.core.PrivilegeStartupException

class PrivilegePendingHandshakeTest {
    @Test
    fun awaitTimesOutWithoutAHandshake(): Unit = runBlocking {
        val pendingHandshake = PrivilegePendingHandshake()

        assertThrows(PrivilegeStartupException::class.java) {
            runBlocking {
                pendingHandshake.await(timeoutMillis = 1_000L)
            }
        }
    }
}
