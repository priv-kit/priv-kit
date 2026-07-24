package priv.kit.core.binder

import android.os.DeadObjectException
import android.os.RemoteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeBinderCallTest {
    @Test
    fun successfulCallDoesNotInvokeFallback() {
        val fallbackCalls = AtomicInteger(0)

        val result = PrivilegeBinderCall.orElse(
            fallback = {
                fallbackCalls.incrementAndGet()
                "fallback"
            },
        ) {
            "success"
        }

        assertEquals("success", result)
        assertEquals(0, fallbackCalls.get())
    }

    @Test
    fun serverUnavailableInvokesTypedFallback() {
        val exception = PrivilegeServerUnavailableException()
        var observedFailure: PrivilegeBinderCallFailure? = null

        val result = PrivilegeBinderCall.orElse(
            fallback = { failure ->
                observedFailure = failure
                "fallback"
            },
        ) {
            throw exception
        }

        assertEquals("fallback", result)
        val failure = observedFailure as PrivilegeBinderCallFailure.ServerUnavailable
        assertSame(exception, failure.exception)
    }

    @Test
    fun deadBinderInvokesTypedFallback() {
        val exception = DeadObjectException("endpoint died")
        var observedFailure: PrivilegeBinderCallFailure? = null

        val result = PrivilegeBinderCall.orElse(
            fallback = { failure ->
                observedFailure = failure
                "fallback"
            },
        ) {
            throw exception
        }

        assertEquals("fallback", result)
        val failure = observedFailure as PrivilegeBinderCallFailure.BinderDied
        assertSame(exception, failure.exception)
    }

    @Test
    fun ordinaryRemoteExceptionPropagatesUnchanged() {
        val exception = RemoteException("transport failed")

        val thrown = assertThrows(RemoteException::class.java) {
            PrivilegeBinderCall.orElse(
                fallback = { throw AssertionError("fallback should not be called") },
            ) {
                throw exception
            }
        }

        assertSame(exception, thrown)
    }

    @Test
    fun fallbackExceptionPropagatesUnchanged() {
        val fallbackException = IllegalStateException("fallback failed")

        val thrown = assertThrows(IllegalStateException::class.java) {
            PrivilegeBinderCall.orElse(
                fallback = { throw fallbackException },
            ) {
                throw DeadObjectException("endpoint died")
            }
        }

        assertSame(fallbackException, thrown)
    }
}
