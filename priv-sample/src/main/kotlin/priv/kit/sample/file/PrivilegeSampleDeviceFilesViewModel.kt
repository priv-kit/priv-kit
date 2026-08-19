package priv.kit.sample.file

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.file.PrivilegeFileEntry
import priv.kit.core.file.PrivilegeFileMetadata
import priv.kit.core.file.PrivilegeFileType
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal class PrivilegeSampleDeviceFilesViewModel : ViewModel() {
    var state by mutableStateOf(PrivilegeSampleDeviceFilesState())
        private set

    private var operationJob: Job? = null

    fun setServerRunning(running: Boolean) {
        if (state.serverRunning == running) return
        operationJob?.cancel()
        if (!running) {
            state = state.copy(
                serverRunning = false,
                entries = emptyList(),
                directoryTruncated = false,
                hasDirectorySnapshot = false,
                pendingDirectory = null,
                isLoadingDirectory = false,
                directoryError = null,
                pathError = null,
                preview = null,
            )
            return
        }
        state = state.copy(serverRunning = true)
        loadDirectory(state.currentDirectory, DirectoryLoadReason.RECONNECT)
    }

    fun updateDirectoryText(value: String) {
        state = state.copy(
            directoryText = value.replace('\n', ' ').replace('\r', ' '),
            pathError = null,
        )
    }

    fun submitDirectory() {
        if (!state.serverRunning || state.isLoadingDirectory) return
        val path = state.directoryText.trim()
        if (!path.startsWith('/')) {
            state = state.copy(pathError = "Enter an absolute directory path.")
            return
        }
        loadDirectory(path, DirectoryLoadReason.PATH_INPUT)
    }

    fun refreshDirectory() {
        if (!state.serverRunning || state.isLoadingDirectory) return
        loadDirectory(state.currentDirectory, DirectoryLoadReason.REFRESH)
    }

    fun openParentDirectory() {
        if (!state.serverRunning || state.isLoadingDirectory) return
        val parent = Privilege.file(state.currentDirectory).parent ?: return
        loadDirectory(parent, DirectoryLoadReason.NAVIGATION)
    }

    fun navigateBack(): Boolean {
        if (!state.serverRunning) return false
        val parent = Privilege.file(state.currentDirectory).parent
        val targetDirectory = directoryBackTarget(
            currentDirectory = state.currentDirectory,
            parentDirectory = parent,
        ) ?: return false
        loadDirectory(targetDirectory, DirectoryLoadReason.NAVIGATION)
        return true
    }

    fun openEntry(entry: PrivilegeFileEntry) {
        if (!state.serverRunning || state.isLoadingDirectory) return
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                val metadata = entry.metadata
                val resolved = when (metadata?.type) {
                    null -> withContext(Dispatchers.IO) {
                        Privilege.file(entry.absolutePath).metadata(followSymbolicLinks = true)
                    }

                    PrivilegeFileType.DIRECTORY,
                    PrivilegeFileType.REGULAR_FILE,
                    -> metadata

                    PrivilegeFileType.SYMBOLIC_LINK -> withContext(Dispatchers.IO) {
                        Privilege.file(entry.absolutePath).metadata(followSymbolicLinks = true)
                    }

                    else -> {
                        state = state.copy(
                            notice = "${entry.name} is not a regular file or directory.",
                        )
                        return@launch
                    }
                }

                when (resolved.type) {
                    PrivilegeFileType.DIRECTORY -> {
                        loadDirectoryContent(
                            path = entry.absolutePath,
                            reason = DirectoryLoadReason.NAVIGATION,
                        )
                    }

                    PrivilegeFileType.REGULAR_FILE -> {
                        loadPreviewContent(entry.absolutePath, entry.name)
                    }

                    else -> {
                        state = state.copy(
                            notice = "${entry.name} points to an unsupported file type.",
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                state = state.copy(directoryError = throwable.toDisplayMessage())
            }
        }
    }

    fun closePreview() {
        operationJob?.cancel()
        state = state.copy(preview = null)
    }

    fun retryPreview() {
        val preview = state.preview ?: return
        val path = preview.absolutePath
        val name = preview.name
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            loadPreviewContent(path, name)
        }
    }

    fun selectPreviewMode(mode: PrivilegeSampleFilePreviewMode) {
        val preview = state.preview as? PrivilegeSampleFilePreview.Content ?: return
        state = state.copy(preview = preview.copy(mode = mode))
    }

    fun consumeNotice() {
        state = state.copy(notice = null)
    }

    override fun onCleared() {
        operationJob?.cancel()
        super.onCleared()
    }

    private fun loadDirectory(path: String, reason: DirectoryLoadReason) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            loadDirectoryContent(path, reason)
        }
    }

    private suspend fun loadDirectoryContent(path: String, reason: DirectoryLoadReason) {
        state = state.copy(
            isLoadingDirectory = true,
            pendingDirectory = path,
            directoryError = null,
            pathError = null,
            preview = null,
        )
        try {
            val result = withContext(Dispatchers.IO) {
                val directory = Privilege.file(path)
                check(directory.metadata(followSymbolicLinks = true).type == PrivilegeFileType.DIRECTORY) {
                    "$path is not a directory."
                }
                val walked = directory.walk(maxDepth = 1)
                    .take(MAX_DIRECTORY_ENTRIES + 1)
                    .toList()
                DirectoryLoadResult(
                    entries = walked
                        .take(MAX_DIRECTORY_ENTRIES)
                        .sortedWith(DIRECTORY_ENTRY_COMPARATOR),
                    truncated = walked.size > MAX_DIRECTORY_ENTRIES,
                )
            }
            state = state.copy(
                currentDirectory = path,
                directoryText = path,
                entries = result.entries,
                directoryTruncated = result.truncated,
                hasDirectorySnapshot = true,
                pendingDirectory = null,
                isLoadingDirectory = false,
                directoryError = null,
                pathError = null,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val message = throwable.toDisplayMessage()
            state = when (reason) {
                DirectoryLoadReason.PATH_INPUT -> state.copy(
                    pendingDirectory = null,
                    isLoadingDirectory = false,
                    pathError = message,
                )

                DirectoryLoadReason.NAVIGATION -> state.copy(
                    directoryText = state.currentDirectory,
                    pendingDirectory = null,
                    isLoadingDirectory = false,
                    notice = message,
                )

                DirectoryLoadReason.RECONNECT -> state.copy(
                    entries = emptyList(),
                    directoryTruncated = false,
                    hasDirectorySnapshot = false,
                    pendingDirectory = null,
                    isLoadingDirectory = false,
                    directoryError = message,
                )

                DirectoryLoadReason.REFRESH -> if (state.hasDirectorySnapshot) {
                    state.copy(
                        pendingDirectory = null,
                        isLoadingDirectory = false,
                        notice = message,
                    )
                } else {
                    state.copy(
                        pendingDirectory = null,
                        isLoadingDirectory = false,
                        directoryError = message,
                    )
                }
            }
        }
    }

    private suspend fun loadPreviewContent(path: String, name: String) {
        state = state.copy(
            preview = PrivilegeSampleFilePreview.Loading(
                absolutePath = path,
                name = name,
            ),
        )
        try {
            val result = withContext(Dispatchers.IO) {
                val file = Privilege.file(path)
                val metadata = file.metadata(followSymbolicLinks = true)
                check(metadata.type == PrivilegeFileType.REGULAR_FILE) {
                    "$path is not a regular file."
                }
                val buffer = ByteArray(MAX_PREVIEW_BYTES + 1)
                var byteCount = 0
                file.openInputStream().use { input ->
                    while (byteCount < buffer.size) {
                        val read = input.read(buffer, byteCount, buffer.size - byteCount)
                        if (read < 0) break
                        if (read > 0) byteCount += read
                    }
                }
                val truncated = byteCount > MAX_PREVIEW_BYTES
                val bytes = buffer.copyOf(minOf(byteCount, MAX_PREVIEW_BYTES))
                PreviewLoadResult(
                    metadata = metadata,
                    bytes = bytes,
                    truncated = truncated,
                    initialMode = bytes.defaultPreviewMode(),
                )
            }
            state = state.copy(
                preview = PrivilegeSampleFilePreview.Content(
                    absolutePath = path,
                    name = name,
                    metadata = result.metadata,
                    bytes = result.bytes,
                    truncated = result.truncated,
                    mode = result.initialMode,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            state = state.copy(
                preview = PrivilegeSampleFilePreview.Error(
                    absolutePath = path,
                    name = name,
                    message = throwable.toDisplayMessage(),
                ),
            )
        }
    }
}

internal data class PrivilegeSampleDeviceFilesState(
    val serverRunning: Boolean = false,
    val currentDirectory: String = ROOT_DIRECTORY,
    val directoryText: String = ROOT_DIRECTORY,
    val entries: List<PrivilegeFileEntry> = emptyList(),
    val directoryTruncated: Boolean = false,
    val hasDirectorySnapshot: Boolean = false,
    val pendingDirectory: String? = null,
    val isLoadingDirectory: Boolean = false,
    val directoryError: String? = null,
    val pathError: String? = null,
    val preview: PrivilegeSampleFilePreview? = null,
    val notice: String? = null,
)

internal fun directoryBackTarget(
    currentDirectory: String,
    parentDirectory: String?,
): String? = if (currentDirectory == ROOT_DIRECTORY) null else parentDirectory

internal sealed interface PrivilegeSampleFilePreview {
    val absolutePath: String
    val name: String

    data class Loading(
        override val absolutePath: String,
        override val name: String,
    ) : PrivilegeSampleFilePreview

    data class Content(
        override val absolutePath: String,
        override val name: String,
        val metadata: PrivilegeFileMetadata,
        val bytes: ByteArray,
        val truncated: Boolean,
        val mode: PrivilegeSampleFilePreviewMode,
    ) : PrivilegeSampleFilePreview

    data class Error(
        override val absolutePath: String,
        override val name: String,
        val message: String,
    ) : PrivilegeSampleFilePreview
}

internal enum class PrivilegeSampleFilePreviewMode(val label: String) {
    TEXT("Text"),
    HEX("HEX"),
}

private enum class DirectoryLoadReason {
    RECONNECT,
    PATH_INPUT,
    NAVIGATION,
    REFRESH,
}

private data class DirectoryLoadResult(
    val entries: List<PrivilegeFileEntry>,
    val truncated: Boolean,
)

private data class PreviewLoadResult(
    val metadata: PrivilegeFileMetadata,
    val bytes: ByteArray,
    val truncated: Boolean,
    val initialMode: PrivilegeSampleFilePreviewMode,
)

private fun ByteArray.defaultPreviewMode(): PrivilegeSampleFilePreviewMode {
    val sample = copyOf(minOf(size, BINARY_SAMPLE_BYTES))
    if (sample.any { it == 0.toByte() }) return PrivilegeSampleFilePreviewMode.HEX
    val decoded = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(sample))
            .toString()
    }.getOrNull() ?: return PrivilegeSampleFilePreviewMode.HEX
    if (decoded.isEmpty()) return PrivilegeSampleFilePreviewMode.TEXT
    val controlCharacters = decoded.count { character ->
        character.code < ASCII_SPACE && character !in ALLOWED_TEXT_CONTROLS
    }
    return if (controlCharacters.toDouble() / decoded.length > MAX_CONTROL_CHARACTER_RATIO) {
        PrivilegeSampleFilePreviewMode.HEX
    } else {
        PrivilegeSampleFilePreviewMode.TEXT
    }
}

private fun Throwable.toDisplayMessage(): String =
    message?.takeIf(String::isNotBlank) ?: javaClass.simpleName

private val DIRECTORY_ENTRY_COMPARATOR = Comparator<PrivilegeFileEntry> { first, second ->
    val firstGroup = if (first.metadata?.type == PrivilegeFileType.DIRECTORY) 0 else 1
    val secondGroup = if (second.metadata?.type == PrivilegeFileType.DIRECTORY) 0 else 1
    if (firstGroup != secondGroup) {
        firstGroup - secondGroup
    } else {
        val nameComparison = first.name.compareTo(second.name, ignoreCase = true)
        if (nameComparison != 0) {
            nameComparison
        } else {
            first.absolutePath.compareTo(second.absolutePath)
        }
    }
}

internal const val MAX_PREVIEW_BYTES: Int = 64 * 1024
private const val MAX_DIRECTORY_ENTRIES: Int = 20_000
private const val BINARY_SAMPLE_BYTES: Int = 4 * 1024
private const val ASCII_SPACE: Int = 0x20
private const val MAX_CONTROL_CHARACTER_RATIO: Double = 0.10
private const val ROOT_DIRECTORY: String = "/"
private val ALLOWED_TEXT_CONTROLS: Set<Char> = setOf('\t', '\n', '\r')
