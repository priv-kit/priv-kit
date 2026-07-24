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
    public class ServerUnavailable internal constructor(
        override val exception: PrivilegeServerUnavailableException,
    ) : PrivilegeBinderCallFailure

    /**
     * The directly called Binder endpoint died.
     */
    public class BinderDied internal constructor(
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
     */
    public fun <T> orElse(
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
