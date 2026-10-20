package priv.kit.input

import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.RemoteException
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P, Build.VERSION_CODES.UPSIDE_DOWN_CAKE], manifest = Config.NONE,
    shadows = [RealtimeSystemClock::class, InputIdentityShadow::class])
class PrivilegeInputIdentityTest {
    @Before fun caller() {
        ShadowBinder.setCallingUid(12345)
        ShadowBinder.setCallingPid(54321)
        assertNotEquals(12345, Process.myUid())
        assertNotEquals(54321, Process.myPid())
        InputIdentityShadow.resetCounts()
    }

    private fun assertCleared() {
        assertEquals(Process.myUid(), Binder.getCallingUid())
        assertEquals(Process.myPid(), Binder.getCallingPid())
    }

    private fun assertRestored(calls: Int) {
        assertEquals(12345, Binder.getCallingUid())
        assertEquals(54321, Binder.getCallingPid())
        assertEquals(calls, InputIdentityShadow.clears)
        assertEquals(calls, InputIdentityShadow.restores)
    }

    @Test fun commandsRestoreIdentityOnSuccessAndTransportFailure() {
        val failure = RemoteException("lost reply")
        var reject = false
        var dispatches = 0
        fun dispatch() {
            assertCleared()
            dispatches++
            if (reject) throw failure
        }
        val input = inputFixture(inject = { _, _ -> dispatch(); true })
        kotlinx.coroutines.runBlocking { input.keyEvent(KeyEvent.KEYCODE_A) }
        assertRestored(2)
        assertEquals(2, dispatches)
        reject = true
        assertThrows(RemoteException::class.java) { kotlinx.coroutines.runBlocking { input.keyEvent(KeyEvent.KEYCODE_A) } }
        assertRestored(3)
    }

    @Test fun parameterFailureDoesNotClearIdentity() {
        val input = inputFixture()
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { input.keyEvent(intArrayOf()) } }
        assertRestored(0)
    }

    @Test fun cancellationCleanupRestoresIdentity() = kotlinx.coroutines.runBlocking {
        val pressed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var events = 0
        val input = inputFixture(inject = { _, _ ->
            assertCleared()
            events++
            pressed.complete(Unit)
            true
        })
        val job = launch { input.keyEvent(KeyEvent.KEYCODE_A, durationMillis = 5_000) }
        kotlinx.coroutines.withTimeout(5_000) { pressed.await() }
        kotlinx.coroutines.delay(100)
        assertTrue(job.isActive)
        kotlinx.coroutines.withTimeout(1_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(2, events)
        assertRestored(2)
    }

    @Test @Config(sdk = [Build.VERSION_CODES.P])
    fun unsupportedDisplayFailsBeforeClearingIdentity() {
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { inputFixture().tap(Float.NaN, 0f, displayId = 2) } }
        assertRestored(0)
    }
}
