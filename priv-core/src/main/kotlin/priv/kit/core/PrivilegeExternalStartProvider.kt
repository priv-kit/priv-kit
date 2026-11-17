package priv.kit.core

import android.content.Context

/** An external authorization source that can execute the Core starter command. */
public interface PrivilegeExternalStartProvider {
    /** Stable identifier used to restore the startup method across app restarts and upgrades. */
    public val id: String

    /** Display name resolved using the supplied context's language. */
    public fun getLabel(context: Context): CharSequence

    /** Reads the current authorization state without prompting the user. */
    public suspend fun checkAuthorization(context: Context): PrivilegeExternalStartResult

    /** Requests authorization in response to user interaction and returns the resulting state. */
    public suspend fun requestAuthorization(context: Context): PrivilegeExternalStartResult

    /** Executes the command supplied by the startup coordinator and returns its output. */
    @Throws(PrivilegeStartupException::class)
    public suspend fun runStarter(
        context: Context,
        commandLine: String,
        startupLogListener: PrivilegeStartupLogListener? = null,
    ): String
}

/** [authorized] is null when unavailable, false when unauthorized, and true when authorized. */
public data class PrivilegeExternalStartResult(
    public val authorized: Boolean?,
    /** Localized details about the current state or authorization attempt. */
    public val message: CharSequence? = null,
)
