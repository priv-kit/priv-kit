package priv.kit.core

import kotlinx.serialization.Serializable

/**
 * One fatal Java/Kotlin exception recorded by a privileged process.
 *
 * Times are Unix epoch milliseconds; [startedAtEpochMillis] is when the recorder was initialized.
 * [processType] is `server` or `userService`. [stackTrace] includes causes and suppressed exceptions.
 * Readers should ignore unknown JSON fields and check [schemaVersion] (currently 1).
 */
@Serializable
public data class PrivilegeCrashLog(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val userId: Int,
    public val uid: Int,
    public val pid: Int,
    public val processType: String,
    public val serviceClassName: String?,
    public val startedAtEpochMillis: Long,
    public val crashedAtEpochMillis: Long,
    public val threadName: String,
    public val exceptionType: String,
    public val exceptionMessage: String?,
    public val stackTrace: String,
)
