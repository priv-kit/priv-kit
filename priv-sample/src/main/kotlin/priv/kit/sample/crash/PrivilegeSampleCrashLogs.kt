package priv.kit.sample.crash

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.json.Json
import priv.kit.core.PrivilegeCrashLog

private val crashJson = Json { ignoreUnknownKeys = true }
internal const val MAX_CRASH_LOG_BYTES = 1024 * 1024

internal fun isCrashLogFile(
    name: String,
    applicationId: String,
    userId: Int,
    appDirectory: Boolean = false,
): Boolean =
    ((appDirectory && name.matches(Regex("priv-crash_uid[0-9]+_[0-9]{14}\\.json"))) ||
        name.startsWith("priv-crash_${applicationId}_u${userId}_uid") ||
        name.startsWith("priv-crash_${applicationId}_user${userId}_uid")) && name.endsWith(".json")

internal fun readCrashLog(input: InputStream, applicationId: String, userId: Int): PrivilegeCrashLog {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= MAX_CRASH_LOG_BYTES) { "Crash log exceeds 1 MiB" }
        output.write(buffer, 0, count)
    }
    val report = crashJson.decodeFromString(PrivilegeCrashLog.serializer(), output.toString("UTF-8"))
    require(report.schemaVersion == 1) { "Unsupported crash log schema: ${report.schemaVersion}" }
    require(report.applicationId == applicationId && report.userId == userId) {
        "Crash log belongs to another application or Android user"
    }
    return report
}

internal data class PrivilegeSampleCrashEntry(
    val path: String,
    val modifiedAt: Long,
    val report: PrivilegeCrashLog? = null,
    val error: String? = null,
) {
    val name: String get() = path.substringAfterLast('/')
}
