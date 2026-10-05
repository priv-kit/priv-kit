package priv.kit.sample.crash

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import priv.kit.core.PrivilegeCrashLog

class PrivilegeSampleCrashLogsTest {
    private val report = PrivilegeCrashLog(
        schemaVersion = 1, applicationId = "example.app", userId = 10, uid = 2000, pid = 123,
        processType = "server", serviceClassName = null, startedAtEpochMillis = 1_791_200_000_000,
        crashedAtEpochMillis = 1_791_200_001_000, threadName = "sample-embedded-crash",
        exceptionType = "java.lang.IllegalStateException", exceptionMessage = "测试\n\"crash\"",
        stackTrace = "IllegalStateException: 测试\n\tat sample.crash(Test.kt:1)",
    )

    @Test
    fun filtersByExactOwnerAndOnlyPublishedJsonFiles() {
        val shortName = "priv-crash_uid2000_20261005203357.json"
        assertTrue(isCrashLogFile(shortName, "example.app", 10, appDirectory = true))
        assertFalse(isCrashLogFile(shortName, "example.app", 10))
        assertFalse(isCrashLogFile("$shortName.tmp", "example.app", 10, appDirectory = true))
        assertTrue(isCrashLogFile("priv-crash_example.app_u10_uid2000_20261005203357.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app_u100_uid2000_20261005203357.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app.other_u10_uid2000_20261005203357.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app_u10_uid2000_20261005203357.json.tmp", "example.app", 10))
        assertTrue(isCrashLogFile("priv-crash_example.app_user10_uid2000_server_1.json", "example.app", 10))
        assertTrue(isCrashLogFile("priv-crash_example.app_user10_uid2000_abcdefghijklmnopqrstuv.json", "example.app", 10))
        assertTrue(isCrashLogFile("priv-crash_example.app_user10_uid2000_20261005203357.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app_user100_uid2000_server_1.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app.other_user10_uid2000_1.json", "example.app", 10))
        assertFalse(isCrashLogFile("priv-crash_example.app_user10_uid2000_1.json.tmp", "example.app", 10))
    }

    @Test
    fun readsPublicModelWithFutureFieldsAndEscapedText() {
        val text = Json.encodeToString(PrivilegeCrashLog.serializer(), report).dropLast(1) + ",\"future\":true}"
        assertEquals(report, readCrashLog(text.byteInputStream(), "example.app", 10))
    }

    @Test
    fun rejectsWrongOwnerUnsupportedSchemaAndMalformedJson() {
        for (invalid in listOf(report.copy(userId = 0), report.copy(applicationId = "other.app"), report.copy(schemaVersion = 2))) {
            val text = Json.encodeToString(PrivilegeCrashLog.serializer(), invalid)
            assertThrows(IllegalArgumentException::class.java) {
                readCrashLog(text.byteInputStream(), "example.app", 10)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            readCrashLog("{broken".byteInputStream(), "example.app", 10)
        }
    }

    @Test
    fun boundsReadsBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            readCrashLog(ByteArray(MAX_CRASH_LOG_BYTES + 1).inputStream(), "example.app", 10)
        }
    }
}
