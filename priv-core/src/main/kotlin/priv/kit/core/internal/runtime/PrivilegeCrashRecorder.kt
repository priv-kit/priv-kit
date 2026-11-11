package priv.kit.core.internal.runtime

import android.os.Process
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import priv.kit.core.PrivilegeCrashLog
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

internal class PrivilegeCrashRecorder(
    private val packageName: String,
    private val userId: Int,
    private val uid: Int,
    private val pid: Int,
    private val serviceClassName: String? = null,
    private val preferredDirectories: () -> List<File> = { currentDirectories },
    private val fallbackRoot: File = PrivilegeFallbackCrashLogs.root,
) {
    private val startedAt = System.currentTimeMillis()
    private val processType = if (serviceClassName == null) "server" else "userService"
    private val recorded = AtomicBoolean()

    fun record(thread: Thread, throwable: Throwable): Result<File?> = runCatching {
        if (!recorded.compareAndSet(false, true)) return@runCatching null
        val report = PrivilegeCrashLog(
            schemaVersion = 1,
            applicationId = packageName,
            userId = userId,
            uid = uid,
            pid = pid,
            processType = processType,
            serviceClassName = serviceClassName,
            startedAtEpochMillis = startedAt,
            crashedAtEpochMillis = System.currentTimeMillis(),
            threadName = thread.name,
            exceptionType = throwable.javaClass.name,
            exceptionMessage = throwable.message,
            stackTrace = throwable.stackTraceToString(),
        )
        val bytes = report.toJson().toString().toByteArray(Charsets.UTF_8)
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(report.crashedAtEpochMillis))
        val fileName = "priv-crash_uid${uid}_${timestamp}.json"
        val failures = mutableListOf<Exception>()
        for (directory in preferredDirectories()) {
            try {
                return@runCatching write(directory, fileName, bytes)
            } catch (failure: Exception) {
                failures += failure
            }
        }
        val file = try {
            val directory = PrivilegeFallbackCrashLogs.createDirectory(fallbackRoot, packageName, userId)
            write(directory, fileName, bytes)
        } catch (fallbackFailure: Exception) {
            failures.forEach(fallbackFailure::addSuppressed)
            throw fallbackFailure
        }
        appendFallbackIndex(file)
        file
    }

    private fun appendFallbackIndex(file: File) {
        val index = file.toPath().resolveSibling(PrivilegeFallbackCrashLogs.INDEX_NAME)
        try {
            if (Files.exists(index, NOFOLLOW_LINKS) && !Files.isRegularFile(index, NOFOLLOW_LINKS)) {
                throw IOException("Crash log index is not a regular file: $index")
            }
            val line = (file.name + "\n").toByteArray(Charsets.UTF_8)
            Files.write(index, line, CREATE, APPEND, NOFOLLOW_LINKS)
        } catch (failure: Exception) {
            runCatching { Log.w("PrivKit", "Unable to append to crash log index", failure) }
        }
    }

    private fun write(directory: File, fileName: String, bytes: ByteArray): File {
        val file = File(directory, fileName)
        val temporary = File(directory, "$fileName.tmp").toPath()
        var created = false
        try {
            // CREATE_NEW avoids overwriting existing files or following a pre-existing symlink.
            FileChannel.open(temporary, CREATE_NEW, WRITE).use { channel ->
                created = true
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, file.toPath(), ATOMIC_MOVE)
            return file
        } finally {
            if (created) runCatching { Files.deleteIfExists(temporary) }
        }
    }

    fun recordOrLog(thread: Thread, throwable: Throwable): Result<File?> =
        record(thread, throwable).onFailure { failure ->
            runCatching { Log.e("PrivKit", "Unable to persist privileged process crash", failure) }
        }

    companion object {
        @Volatile
        var currentDirectories: List<File> = emptyList()
            private set

        fun updateDirectories(paths: List<String>) {
            currentDirectories = paths.map(::File).filter { it.isAbsolute }
        }

        fun encodeDirectories(directories: List<File>): String =
            JSONArray(directories.map { it.absolutePath }).toString()

        fun install(packageName: String, userId: Int, serviceClassName: String? = null): PrivilegeCrashRecorder {
            val encoded = System.getenv(PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORIES)
            val paths = if (encoded == null) emptyList() else try {
                val array = JSONArray(encoded)
                List(array.length()) { array.getString(it) }
            } catch (error: JSONException) {
                Log.w("PrivKit", "Unable to decode crash log directories", error)
                emptyList()
            }
            updateDirectories(paths)
            val recorder = PrivilegeCrashRecorder(
                packageName = packageName,
                userId = userId,
                uid = Process.myUid(),
                pid = Process.myPid(),
                serviceClassName = serviceClassName,
            )
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recorder.recordOrLog(thread, throwable).onFailure {
                        runCatching {
                            Log.e("PrivKit", "Privileged process crashed on thread ${thread.name}", throwable)
                        }
                    }
                } finally {
                    try {
                        previous?.uncaughtException(thread, throwable)
                    } finally {
                        Process.killProcess(Process.myPid())
                        exitProcess(1)
                    }
                }
            }
            return recorder
        }
    }
}

private fun PrivilegeCrashLog.toJson(): JSONObject = JSONObject().apply {
    put("schemaVersion", schemaVersion)
    put("applicationId", applicationId)
    put("userId", userId)
    put("uid", uid)
    put("pid", pid)
    put("processType", processType)
    put("serviceClassName", serviceClassName ?: JSONObject.NULL)
    put("startedAtEpochMillis", startedAtEpochMillis)
    put("crashedAtEpochMillis", crashedAtEpochMillis)
    put("threadName", threadName)
    put("exceptionType", exceptionType)
    put("exceptionMessage", exceptionMessage ?: JSONObject.NULL)
    put("stackTrace", stackTrace)
}
