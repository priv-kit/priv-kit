package priv.kit.sample.crash

import android.app.Application
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.PrivilegeConfig

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

    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) { scan() }
                entries = result.first
                errors = result.second
            } finally {
                // A cancelled, older scan must not clear a newer scan's loading indicator.
                if (currentCoroutineContext()[Job]?.isActive == true) loading = false
            }
        }
    }

    private suspend fun scan(): Pair<List<PrivilegeSampleCrashEntry>, List<String>> {
        val app = getApplication<Application>()
        val applicationId = app.packageName
        val userId = Process.myUserHandle().hashCode()
        val found = mutableMapOf<String, PrivilegeSampleCrashEntry>()
        val failures = mutableListOf<String>()

        suspend fun read(file: File) {
            currentCoroutineContext().ensureActive()
            val path = file.absolutePath
            if (found[path]?.report != null) return
            val modified = file.lastModified()
            try {
                val report = Files.newInputStream(file.toPath(), NOFOLLOW_LINKS)
                    .use { readCrashLog(it, applicationId, userId) }
                found[path] = PrivilegeSampleCrashEntry(path, modified, report)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                found[path] = PrivilegeSampleCrashEntry(path, modified, error = exception.toString())
            }
        }

        for (directory in PrivilegeConfig.crashLogDirectories.distinct()) {
            try {
                Files.newDirectoryStream(directory.toPath()).use { files ->
                    for (path in files) {
                        currentCoroutineContext().ensureActive()
                        if (isCrashLogFile(path.fileName.toString()) &&
                            Files.isRegularFile(path, NOFOLLOW_LINKS)
                        ) {
                            read(path.toFile())
                        }
                    }
                }
            } catch (_: NoSuchFileException) {
                // There are no reports in a directory that does not exist.
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                failures += "${directory.path}: $exception"
            }
        }
        try {
            for (file in Privilege.listFallbackCrashLogFiles()) {
                read(file)
            }
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            failures += exception.toString()
        }
        return found.values.sortedByDescending { it.report?.crashedAtEpochMillis ?: it.modifiedAt } to failures
    }
}
