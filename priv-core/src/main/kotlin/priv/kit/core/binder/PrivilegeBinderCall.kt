package priv.kit.core.binder

import android.os.DeadObjectException

/**
 * A privileged channel or Binder endpoint failure handled by [PrivilegeBinderCall.orElse].
 */
public sealed interface PrivilegeBinderCallFailure {
    public val exception: Throwable

    /**
     * The privileged channel is unavailable, including a closed UserService connection.
     */
    public class Unavailable @PublishedApi internal constructor(
        override val exception: PrivilegeUnavailableException,
    ) : PrivilegeBinderCallFailure

    /**
     * A target Binder or an otherwise unclassified Binder transaction reported endpoint failure.
     */
    public class BinderDied @PublishedApi internal constructor(
        override val exception: DeadObjectException,
    ) : PrivilegeBinderCallFailure
}

/**
 * Executes Binder calls with an explicit fallback for an unavailable channel or endpoint.
 */
public object PrivilegeBinderCall {
    /**
     * Executes [call], or invokes [fallback] when its privileged channel is unavailable or a
     * Binder endpoint has died.
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
        } catch (exception: PrivilegeUnavailableException) {
            fallback(PrivilegeBinderCallFailure.Unavailable(exception))
        } catch (exception: DeadObjectException) {
            fallback(PrivilegeBinderCallFailure.BinderDied(exception))
        }
}
