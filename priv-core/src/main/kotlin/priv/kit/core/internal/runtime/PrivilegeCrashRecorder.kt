package priv.kit.core.internal.runtime

import android.os.Process
import android.util.Log
import org.json.JSONObject
import priv.kit.core.PrivilegeCrashLog
import priv.kit.core.internal.core.PrivilegeHandshakeContract
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
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
    private val preferredDirectory: () -> File? = { currentDirectory },
    private val fallbackDirectory: File = File("/data/local/tmp"),
) {
    private val safeAppId = packageName.map {
        if (it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '_') it else '_'
    }.joinToString("")
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
        val fileName = "priv-crash_${safeAppId}_u${userId}_uid${uid}_${timestamp}.json"
        val preferred = preferredDirectory()
        var preferredFailure: Exception? = null
        if (preferred != null && preferred != fallbackDirectory) {
            try {
                return@runCatching write(preferred, "priv-crash_uid${uid}_${timestamp}.json", bytes)
            } catch (failure: Exception) {
                preferredFailure = failure
            }
        }
        try {
            write(fallbackDirectory, fileName, bytes)
        } catch (fallbackFailure: Exception) {
            preferredFailure?.let(fallbackFailure::addSuppressed)
            throw fallbackFailure
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

    fun recordOrLog(thread: Thread, throwable: Throwable) {
        record(thread, throwable).onFailure { failure ->
            runCatching { Log.e("PrivKit", "Unable to persist privileged process crash", failure) }
        }
    }

    companion object {
        @Volatile
        var currentDirectory: File? = null
            private set

        fun updateDirectory(path: String?) {
            currentDirectory = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isAbsolute }
        }

        fun install(packageName: String, userId: Int, serviceClassName: String? = null): PrivilegeCrashRecorder {
            updateDirectory(System.getenv(PrivilegeHandshakeContract.ENV_CRASH_LOG_DIRECTORY))
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
                    recorder.recordOrLog(thread, throwable)
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
