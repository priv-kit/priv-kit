package priv.kit.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import priv.kit.ui.adb.retryOnLocalNetworkPermissionGrant

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PrivilegeUiPermissionRetryTest {
    @Test
    fun grantCancelsAndJoinsOldDiscoveryBeforeRetry() = runTest {
        val missing = MutableStateFlow(true)
        val events = mutableListOf<String>()
        var attempts = 0
        val result = async {
            retryOnLocalNetworkPermissionGrant(missing) {
                attempts++
                events += "start$attempts"
                if (attempts == 1) {
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) { delay(100); events += "cleaned" }
                    }
                }
                "found"
            }
        }
        runCurrent()
        missing.value = false
        runCurrent()
        assertEquals(listOf("start1"), events)
        advanceTimeBy(100)
        runCurrent()
        assertEquals("found", result.await())
        assertEquals(listOf("start1", "cleaned", "start2"), events)
    }

    @Test
    fun revocationAndUnchangedPermissionDoNotRestartAnAttempt() = runTest {
        val missing = MutableStateFlow(false)
        val finished = CompletableDeferred<String>()
        var attempts = 0
        val result = async {
            retryOnLocalNetworkPermissionGrant(missing) { attempts++; finished.await() }
        }
        runCurrent()
        missing.value = false
        runCurrent()
        missing.value = true
        runCurrent()
        assertEquals(1, attempts)
        finished.complete("connected")
        assertEquals("connected", result.await())
        missing.value = false
        runCurrent()
        assertEquals(1, attempts)
    }

    @Test
    fun userCancellationCannotBeResurrectedByGrant() = runTest {
        val missing = MutableStateFlow(true)
        var attempts = 0
        val result = async {
            retryOnLocalNetworkPermissionGrant(missing) { attempts++; awaitCancellation() }
        }
        runCurrent()
        result.cancelAndJoin()
        missing.value = false
        runCurrent()
        assertEquals(1, attempts)
        assertTrue(result.isCancelled)
    }

    @Test
    fun missingPermissionDoesNotBlockSuccessOrSwallowTransportFailure() = runTest {
        val missing = MutableStateFlow(true)
        assertEquals(42, retryOnLocalNetworkPermissionGrant(missing) { 42 })
        val failure = java.io.IOException("discovery failed")
        val actual = runCatching {
            retryOnLocalNetworkPermissionGrant(missing) { throw failure }
        }.exceptionOrNull()
        assertTrue(actual is java.io.IOException)
        assertEquals(failure.message, actual?.message)
    }
}
