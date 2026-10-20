package priv.kit.core.binder

import android.os.DeadObjectException

/**
 * A Binder endpoint failure handled by [PrivilegeBinderCall.orElse].
 */
public sealed interface PrivilegeBinderCallFailure {
    public val exception: Throwable

    /**
     * The current Privileged Server was missing or became unavailable.
     */
    public class ServerUnavailable @PublishedApi internal constructor(
        override val exception: PrivilegeServerUnavailableException,
    ) : PrivilegeBinderCallFailure

    /**
     * A Binder transaction reported endpoint death. This alone does not identify
     * whether the privileged server or the forwarded target died.
     */
    public class BinderDied @PublishedApi internal constructor(
        override val exception: DeadObjectException,
    ) : PrivilegeBinderCallFailure
}

/**
 * Executes Binder calls with an explicit fallback for endpoint death.
 */
public object PrivilegeBinderCall {
    /**
     * Executes [call], or invokes [fallback] when the Privileged Server is unavailable or the
     * directly called Binder endpoint has died.
     *
     * Other failures, including ordinary `RemoteException` instances and application exceptions,
     * are propagated unchanged.
     * Suspending operations may be called from [call] when used in a coroutine.
     * This function neither unwraps causes nor retries failed operations.
     */
    public inline fun <T> orElse(
        fallback: (failure: PrivilegeBinderCallFailure) -> T,
        call: () -> T,
    ): T =
        try {
            call()
        } catch (exception: PrivilegeServerUnavailableException) {
            fallback(PrivilegeBinderCallFailure.ServerUnavailable(exception))
        } catch (exception: DeadObjectException) {
            fallback(PrivilegeBinderCallFailure.BinderDied(exception))
        }
}
