package priv.kit.core.internal.runtime

import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeCrashLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivilegeCrashRecorderTest {
    private val forwardCompatibleJson = Json { ignoreUnknownKeys = true }
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun androidJsonRoundTripsThroughGeneratedSerializer() {
        val recorder = recorder()
        val error = IllegalStateException("失败 \"quoted\"\nline\\end", IllegalArgumentException("cause"))
        error.addSuppressed(IllegalStateException("suppressed"))
        val file = recorder.record(Thread.currentThread(), error).getOrThrow()!!
        val report = Json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())

        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date(report.crashedAtEpochMillis))
        assertEquals("priv-crash_uid2000_${timestamp}.json", file.name)
        assertEquals(File(temporaryFolder.root, "priv-kit/example.app/10/crashes"), file.parentFile)
        assertEquals("json", file.extension)
        assertEquals(1, report.schemaVersion)
        assertEquals("example.app", report.applicationId)
        assertEquals(10, report.userId)
        assertEquals(2000, report.uid)
        assertEquals(123, report.pid)
        assertEquals("server", report.processType)
        assertNull(report.serviceClassName)
        assertEquals(Thread.currentThread().name, report.threadName)
        assertEquals(error.javaClass.name, report.exceptionType)
        assertEquals(error.message, report.exceptionMessage)
        assertEquals(error.stackTraceToString(), report.stackTrace)
        assertTrue(report.startedAtEpochMillis > Int.MAX_VALUE.toLong())
        assertTrue(report.crashedAtEpochMillis >= report.startedAtEpochMillis)
        assertNull(recorder.record(Thread.currentThread(), error).getOrThrow())
        val index = File(file.parentFile, "index.txt")
        assertEquals("${file.name}\n", index.readText())
        assertEquals(setOf(file, index), file.parentFile!!.listFiles()!!.toSet())

        val extended = JSONObject(file.readText()).put("futureField", true).toString()
        assertEquals(report, forwardCompatibleJson.decodeFromString(PrivilegeCrashLog.serializer(), extended))
    }

    @Test
    fun nullableMessageAndUserServiceIdentityRoundTrip() {
        val recorder = recorder(serviceClassName = "example.MyService")
        val file = recorder.record(Thread.currentThread(), Exception()).getOrThrow()!!
        val report = Json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
        assertEquals("userService", report.processType)
        assertEquals("example.MyService", report.serviceClassName)
        assertNull(report.exceptionMessage)
        assertTrue(JSONObject(file.readText()).has("exceptionMessage"))
        assertTrue(JSONObject(file.readText()).isNull("exceptionMessage"))
    }

    @Test
    fun prefersHostDirectoryAndLeavesNoTemporaryFile() {
        val preferred = temporaryFolder.newFolder("preferred")
        val fallback = temporaryFolder.newFolder("fallback")
        val second = temporaryFolder.newFolder("second")
        val file = recorder(fallback, { listOf(preferred, second) })
            .record(Thread.currentThread(), Exception()).getOrThrow()!!
        assertEquals(preferred, file.parentFile)
        val report = Json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
        val timestamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date(report.crashedAtEpochMillis))
        assertEquals("priv-crash_uid2000_${timestamp}.json", file.name)
        assertEquals(listOf(file), preferred.listFiles()!!.toList())
        assertTrue(second.listFiles()!!.isEmpty())
        assertTrue(fallback.listFiles()!!.isEmpty())
    }

    @Test
    fun unavailableFirstDirectoryTriesNextDirectoryBeforeFallback() {
        val unavailable = temporaryFolder.newFile("not-a-directory")
        val next = temporaryFolder.newFolder("next")
        val fallback = temporaryFolder.newFolder("fallback")
        val file = recorder(fallback, { listOf(unavailable, next) })
            .record(Thread.currentThread(), Exception()).getOrThrow()!!
        assertEquals(next, file.parentFile)
        assertTrue(fallback.listFiles()!!.isEmpty())
    }

    @Test
    fun unavailableHostDirectoriesCreateFallbackAtCrashTime() {
        val unavailable = temporaryFolder.newFile("not-a-directory")
        val fallback = temporaryFolder.newFolder("fallback")
        val missing = File(temporaryFolder.root, "missing")
        val file = recorder(fallback, { listOf(unavailable, missing) })
            .record(Thread.currentThread(), Exception()).getOrThrow()!!
        assertEquals(File(fallback, "priv-kit/example.app/10/crashes"), file.parentFile)
        assertTrue(file.name.startsWith("priv-crash_uid2000_"))
        assertFalse(missing.exists())
        Json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
    }

    @Test
    fun fallbackIndexAppendsReportsFromDifferentProcesses() {
        val first = recorder(uid = 0).record(Thread.currentThread(), Exception()).getOrThrow()!!
        val second = recorder().record(Thread.currentThread(), Exception()).getOrThrow()!!
        assertEquals("${first.name}\n${second.name}\n", File(first.parentFile, "index.txt").readText())
    }

    @Test
    fun failedIndexAppendPreservesPublishedReport() {
        val directory = temporaryFolder.newFolder("priv-kit", "example.app", "10", "crashes")
        assertTrue(File(directory, "index.txt").mkdir())

        val file = recorder().record(Thread.currentThread(), Exception()).getOrThrow()!!

        assertTrue(file.isFile)
    }

    @Test
    fun observesDirectoriesUpdatedAfterRecorderInitialization() {
        var directories = emptyList<File>()
        val recorder = recorder(preferred = { directories })
        val directory = temporaryFolder.newFolder("reconnected")
        directories = listOf(directory)
        val file = recorder.record(Thread.currentThread(), Exception()).getOrThrow()!!
        assertEquals(directory, file.parentFile)
    }

    @Test
    fun failedFallbackKeepsAllPreferredWriteErrors() {
        val missing = File(temporaryFolder.root, "missing")
        val result = recorder(missing, { listOf(File(missing, "first"), File(missing, "second")) })
            .record(Thread.currentThread(), Exception("fatal"))
        assertTrue(result.isFailure)
        assertEquals(2, result.exceptionOrNull()!!.suppressed.size)
        assertFalse(missing.exists())
    }

    private fun recorder(
        fallback: File = temporaryFolder.root,
        preferred: () -> List<File> = { emptyList() },
        serviceClassName: String? = null,
        uid: Int = 2000,
    ) = PrivilegeCrashRecorder(
        packageName = "example.app",
        userId = 10,
        uid = uid,
        pid = 123,
        serviceClassName = serviceClassName,
        preferredDirectories = preferred,
        fallbackRoot = fallback,
    )
}
