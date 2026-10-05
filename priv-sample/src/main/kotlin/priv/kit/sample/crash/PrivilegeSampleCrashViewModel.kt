package priv.kit.sample.crash

import android.app.Application
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeConfig
import priv.kit.core.command.PrivilegeCommand
import priv.kit.core.file.PrivilegeFileType
import priv.kit.sample.R

internal class PrivilegeSampleCrashViewModel(application: Application) : AndroidViewModel(application) {
    var entries by mutableStateOf<List<PrivilegeSampleCrashEntry>>(emptyList())
        private set
    var errors by mutableStateOf<List<String>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var selected by mutableStateOf<PrivilegeSampleCrashEntry?>(null)
        private set
    private var loadJob: Job? = null

    fun select(entry: PrivilegeSampleCrashEntry) { selected = entry }
    fun closeDetails() { selected = null }

    fun refresh(serverRunning: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) { scan(serverRunning) }
                entries = result.first
                errors = result.second
            } finally {
                // A cancelled, older scan must not clear a newer scan's loading indicator.
                if (currentCoroutineContext()[Job]?.isActive == true) loading = false
            }
        }
    }

    private suspend fun scan(serverRunning: Boolean): Pair<List<PrivilegeSampleCrashEntry>, List<String>> {
        val app = getApplication<Application>()
        val applicationId = app.packageName
        val userId = Process.myUserHandle().hashCode()
        val found = mutableListOf<PrivilegeSampleCrashEntry>()
        val failures = mutableListOf<String>()

        fun read(path: String, modified: Long, open: () -> InputStream) {
            try {
                val report = open().use { readCrashLog(it, applicationId, userId) }
                found += PrivilegeSampleCrashEntry(path, modified, report)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                found += PrivilegeSampleCrashEntry(path, modified, error = exception.toString())
            }
        }

        try {
            val directory = PrivilegeConfig.crashLogDirectory
            if (directory != null && directory.exists()) {
                val files = directory.listFiles()
                    ?: error(app.getString(R.string.sample_crash_directory_unreadable, directory.path))
                for (file in files) {
                    currentCoroutineContext().ensureActive()
                    if (isCrashLogFile(file.name, applicationId, userId, appDirectory = true) &&
                        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)
                    ) {
                        read(file.absolutePath, file.lastModified(), file::inputStream)
                    }
                }
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            failures += exception.toString()
        }

        if (serverRunning) {
            try {
                // walk() opens ancestor directories; shell can traverse /data/local but cannot
                // enumerate it. find opens only the requested directory, with no shell expansion.
                val listing = Privilege.startCommand(
                    PrivilegeCommand(listOf(
                        "/system/bin/find", "/data/local/tmp", "-maxdepth", "1", "-type", "f",
                        "(", "-name", "priv-crash_${applicationId}_u${userId}_uid*.json",
                        "-o", "-name", "priv-crash_${applicationId}_user${userId}_uid*.json", ")",
                    )),
                ).use { it.awaitResult() }
                check(listing.exitCode == 0) { listing.stderr.toString(Charsets.UTF_8) }
                check(!listing.stdoutTruncated) { "Crash log directory listing is too large" }
                for (path in listing.stdout.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }) {
                    currentCoroutineContext().ensureActive()
                    val file = Privilege.file(path)
                    if (file.parent == "/data/local/tmp" &&
                        isCrashLogFile(file.name, applicationId, userId) &&
                        found.none { it.path == path }
                    ) {
                        read(path, 0L) {
                            check(file.metadata().type == PrivilegeFileType.REGULAR_FILE) { "Not a regular file" }
                            file.openInputStream()
                        }
                    }
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                failures += "/data/local/tmp: $exception"
            }
        }
        return found.sortedByDescending { it.report?.crashedAtEpochMillis ?: it.modifiedAt } to failures
    }
}
