package priv.kit.sample.file

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import priv.kit.core.Privilege
import priv.kit.core.file.PrivilegeFile
import priv.kit.core.file.PrivilegeFileMetadata
import priv.kit.sample.common.toDiagnosticString

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivilegeSampleFilePage(
    serverRunning: Boolean,
    onBackToHome: () -> Unit,
) {
    var directoryPath by rememberSaveable { mutableStateOf(DEFAULT_TEST_DIRECTORY) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var output by rememberSaveable {
        mutableStateOf("Connect a Privileged Server, then run a File API operation.")
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun runOperation(
        label: String,
        operation: suspend (String) -> String,
    ) {
        if (busy || !serverRunning) return
        val path = directoryPath.trim()
        busy = true
        scope.launch {
            val result = try {
                val text = withContext(Dispatchers.IO) {
                    operation(path)
                }
                "$label\n$text"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                "$label failed\n${throwable.toDiagnosticString()}"
            }
            output = result.takeLast(MAX_OUTPUT_CHARS)
            busy = false
        }
    }

    val actionsEnabled = serverRunning && !busy
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBackToHome) {
                        Text("Home")
                    }
                },
                title = {
                    Text(
                        text = "Test File API",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (serverRunning) "Server connected" else "Server disconnected",
                color = if (serverRunning) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Individual operations use payload.txt and renamed.txt below this " +
                    "directory. Cleanup is non-recursive and only targets those names.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = directoryPath,
                onValueChange = { directoryPath = it.replace('\n', ' ').replace('\r', ' ') },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                singleLine = true,
                label = { Text("Absolute test directory") },
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = actionsEnabled,
                onClick = {
                    runOperation("Full smoke test", ::runFullSmokeTest)
                },
            ) {
                Text(if (busy) "Running…" else "Run Full Smoke Test")
            }

            FileActionButton("Inspect Test Paths", actionsEnabled) {
                runOperation("Inspect", ::inspectTestPaths)
            }
            FileActionButton("Create Directory with mkdir()", actionsEnabled) {
                runOperation("mkdir", ::createDirectory)
            }
            FileActionButton("Create Directory with mkdirs()", actionsEnabled) {
                runOperation("mkdirs", ::createDirectories)
            }
            FileActionButton("Create and Write payload.txt", actionsEnabled) {
                runOperation("Write", ::writePayload)
            }
            FileActionButton("Append payload.txt", actionsEnabled) {
                runOperation("Append", ::appendPayload)
            }
            FileActionButton("Atomically Replace payload.txt", actionsEnabled) {
                runOperation("Atomic replace", ::replacePayloadAtomically)
            }
            FileActionButton("Read payload.txt", actionsEnabled) {
                runOperation("Read", ::readPayload)
            }
            FileActionButton("Rename payload.txt ↔ renamed.txt", actionsEnabled) {
                runOperation("Rename", ::renamePayload)
            }
            FileActionButton("Walk Directory", actionsEnabled) {
                runOperation("Directory walk", ::walkDirectory)
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = actionsEnabled,
                onClick = {
                    runOperation("Cleanup", ::cleanupTestPaths)
                },
            ) {
                Text("Delete Test Files and Directory")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    enabled = !busy,
                    onClick = { output = "" },
                ) {
                    Text("Clear Output")
                }
                TextButton(
                    enabled = output.isNotBlank(),
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("Priv Kit File API output", output),
                        )
                    },
                ) {
                    Text("Copy Output")
                }
            }
            SelectionContainer {
                Text(
                    text = output.ifBlank { "<empty>" },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .padding(16.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FileActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        onClick = onClick,
    ) {
        Text(label)
    }
}

private fun inspectTestPaths(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val payload = directory.resolve(PAYLOAD_FILE_NAME)
    val renamed = directory.resolve(RENAMED_FILE_NAME)
    return buildString {
        appendLine(describeFile(directory))
        appendLine(describeFile(payload))
        append(describeFile(renamed))
    }
}

private fun createDirectory(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val created = directory.mkdir()
    return "path=$directory\nmkdir=$created\n${describeFile(directory)}"
}

private fun createDirectories(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val created = directory.mkdirs()
    return "path=$directory\nmkdirs=$created\n${describeFile(directory)}"
}

private fun writePayload(directoryPath: String): String {
    val directory = requireTestDirectory(directoryPath)
    val payload = directory.resolve(PAYLOAD_FILE_NAME)
    val created = payload.createNewFile()
    val text = "File API sample ${System.currentTimeMillis()}\n"
    payload.openOutputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(text)
    }
    return buildString {
        appendLine("createNewFile=$created")
        appendLine("wrote=${text.toByteArray(Charsets.UTF_8).size} bytes")
        append(describeFile(payload))
    }
}

private fun appendPayload(directoryPath: String): String {
    val payload = Privilege.file(directoryPath).resolve(PAYLOAD_FILE_NAME)
    check(payload.exists()) { "$payload does not exist" }
    val text = "Appended ${System.currentTimeMillis()}\n"
    payload.openOutputStream(append = true).bufferedWriter(Charsets.UTF_8).use { writer ->
        writer.write(text)
    }
    return "appended=${text.toByteArray(Charsets.UTF_8).size} bytes\n${describeFile(payload)}"
}

private fun replacePayloadAtomically(directoryPath: String): String {
    val directory = requireTestDirectory(directoryPath)
    val payload = directory.resolve(PAYLOAD_FILE_NAME)
    val temporary = directory.resolve("$PAYLOAD_FILE_NAME.tmp")
    val text = "Atomically replaced ${System.currentTimeMillis()}\n"
    var replaced = false
    try {
        writeAndSync(temporary, text)
        temporary.replaceAtomically(payload)
        replaced = true
        return "source=$temporary\ndestination=$payload\ncontent:\n" +
            payload.openInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        if (!replaced) {
            runCatching(temporary::delete)
        }
    }
}

private fun readPayload(directoryPath: String): String {
    val payload = Privilege.file(directoryPath).resolve(PAYLOAD_FILE_NAME)
    val text = payload.openInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
    return "path=$payload\nlength=${payload.length()}\ncontent:\n$text"
}

private fun renamePayload(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val payload = directory.resolve(PAYLOAD_FILE_NAME)
    val renamed = directory.resolve(RENAMED_FILE_NAME)
    val source: PrivilegeFile
    val destination: PrivilegeFile
    if (payload.exists()) {
        source = payload
        destination = renamed
    } else {
        source = renamed
        destination = payload
    }
    check(source.exists()) { "Neither $payload nor $renamed exists" }
    check(!destination.exists()) { "$destination already exists" }
    val renamedSuccessfully = source.renameTo(destination)
    return "source=$source\ndestination=$destination\nrenameTo=$renamedSuccessfully"
}

private suspend fun walkDirectory(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val entries = directory.walk(maxDepth = SAMPLE_WALK_MAX_DEPTH)
        .take(MAX_DISPLAYED_ENTRIES + 1)
        .toList()
    return buildString {
        appendLine("path=$directory")
        appendLine("maxDepth=$SAMPLE_WALK_MAX_DEPTH")
        appendLine("entries=${entries.size.coerceAtMost(MAX_DISPLAYED_ENTRIES)}")
        entries.take(MAX_DISPLAYED_ENTRIES).forEach { entry ->
            appendLine(
                "depth=${entry.depth} " + (
                    entry.metadata?.toDisplayText()
                        ?: "${entry.absolutePath} metadata=unavailable"
                    ),
            )
        }
        if (entries.size > MAX_DISPLAYED_ENTRIES) {
            append("… walk stopped after $MAX_DISPLAYED_ENTRIES entries")
        }
    }.trimEnd()
}

private fun cleanupTestPaths(directoryPath: String): String {
    val directory = Privilege.file(directoryPath)
    val payload = directory.resolve(PAYLOAD_FILE_NAME)
    val renamed = directory.resolve(RENAMED_FILE_NAME)
    val temporary = directory.resolve("$PAYLOAD_FILE_NAME.tmp")
    return buildString {
        appendLine("payload.delete=${deleteIfPresent(payload)}")
        appendLine("renamed.delete=${deleteIfPresent(renamed)}")
        appendLine("temporary.delete=${deleteIfPresent(temporary)}")
        append("directory.delete=${deleteIfPresent(directory)}")
    }
}

private suspend fun runFullSmokeTest(directoryPath: String): String {
    val lines = mutableListOf<String>()
    val directory = Privilege.file(directoryPath)
    val suffix = System.currentTimeMillis().toString(36)
    val singleDirectory = directory.resolve("mkdir-$suffix")
    val nestedParent = directory.resolve("mkdirs-$suffix")
    val nestedDirectory = nestedParent.resolve("child")
    val payload = directory.resolve("payload-$suffix.txt")
    val renamed = directory.resolve("renamed-$suffix.txt")
    val atomicReplacement = directory.resolve("atomic-$suffix.tmp")
    val directoryCreated = !directory.exists()

    try {
        if (directoryCreated) {
            check(directory.mkdirs()) { "mkdirs failed for $directory" }
        }
        check(directory.isDirectory()) { "$directory is not a directory" }
        lines += "PASS mkdirs: $directory"

        check(singleDirectory.mkdir()) { "mkdir failed for $singleDirectory" }
        check(singleDirectory.isDirectory()) { "$singleDirectory is not a directory" }
        lines += "PASS mkdir: $singleDirectory"

        check(nestedDirectory.mkdirs()) { "nested mkdirs failed for $nestedDirectory" }
        check(nestedDirectory.isDirectory()) { "$nestedDirectory is not a directory" }
        lines += "PASS nested mkdirs: $nestedDirectory"

        check(payload.createNewFile()) { "createNewFile failed for $payload" }
        val initialText = "first line\n"
        val appendedText = "second line\n"
        payload.openOutputStream().bufferedWriter(Charsets.UTF_8).use { it.write(initialText) }
        payload.openOutputStream(append = true).bufferedWriter(Charsets.UTF_8).use {
            it.write(appendedText)
        }
        val actualText = payload.openInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
        check(actualText == initialText + appendedText) { "stream content mismatch" }
        lines += "PASS create/write/append/read: $payload"

        check(payload.exists() && payload.isFile()) { "$payload is not a regular file" }
        check(!payload.isDirectory()) { "$payload was reported as a directory" }
        val metadata = payload.metadata()
        lines += "PASS query/stat: ${metadata.toDisplayText()}"
        lines += "permissions read=${payload.canRead()} write=${payload.canWrite()} " +
            "execute=${payload.canExecute()} symbolicLink=${payload.isSymbolicLink()}"
        lines += "length=${payload.length()} lastModified=${payload.lastModified()} " +
            "hidden=${payload.isHidden()}"

        check(payload.renameTo(renamed)) { "renameTo failed: $payload -> $renamed" }
        check(!payload.exists() && renamed.exists()) { "rename result was not observable" }
        lines += "PASS renameTo: $renamed"

        val replacementText = "atomic replacement\n"
        writeAndSync(atomicReplacement, replacementText)
        atomicReplacement.replaceAtomically(renamed)
        check(!atomicReplacement.exists()) { "$atomicReplacement still exists after replacement" }
        val replacedText = renamed.openInputStream().bufferedReader(Charsets.UTF_8).use {
            it.readText()
        }
        check(replacedText == replacementText) { "atomic replacement content mismatch" }
        lines += "PASS replaceAtomically: $renamed"

        val entries = directory.walk(maxDepth = 1).toList()
        check(entries.any { it.absolutePath == renamed.absolutePath }) {
            "walk(maxDepth = 1) did not return $renamed"
        }
        val recursiveEntries = directory.walk(maxDepth = 2).toList()
        check(
            recursiveEntries.any {
                it.absolutePath == nestedDirectory.absolutePath && it.depth == 2
            },
        ) { "walk(maxDepth = 2) did not return $nestedDirectory at depth 2" }
        lines += "PASS walk: ${recursiveEntries.size} entries"

        check(renamed.delete()) { "delete failed for $renamed" }
        check(nestedDirectory.delete()) { "delete failed for $nestedDirectory" }
        check(nestedParent.delete()) { "delete failed for $nestedParent" }
        check(singleDirectory.delete()) { "delete failed for $singleDirectory" }
        if (directoryCreated) {
            check(directory.delete()) { "delete failed for $directory" }
        }
        lines += "PASS delete"
        lines += "ALL FILE API CHECKS PASSED"
        return lines.joinToString("\n")
    } catch (throwable: Throwable) {
        throw IllegalStateException(
            (lines + "FAIL ${throwable.message ?: throwable.javaClass.name}").joinToString("\n"),
            throwable,
        )
    } finally {
        runCatching { deleteIfPresent(payload) }
        runCatching { deleteIfPresent(renamed) }
        runCatching { deleteIfPresent(atomicReplacement) }
        runCatching { deleteIfPresent(nestedDirectory) }
        runCatching { deleteIfPresent(nestedParent) }
        runCatching { deleteIfPresent(singleDirectory) }
        if (directoryCreated) {
            runCatching { deleteIfPresent(directory) }
        }
    }
}

private fun writeAndSync(file: PrivilegeFile, text: String) {
    file.openOutputStream(syncOnClose = true).use { output ->
        output.write(text.toByteArray(Charsets.UTF_8))
    }
}

private fun requireTestDirectory(directoryPath: String): PrivilegeFile {
    val directory = Privilege.file(directoryPath)
    if (!directory.exists()) {
        check(directory.mkdirs()) { "Unable to create $directory" }
    }
    check(directory.isDirectory()) { "$directory is not a directory" }
    return directory
}

private fun describeFile(file: PrivilegeFile): String {
    if (!file.exists()) return "path=$file exists=false"
    val metadata = file.metadata()
    return buildString {
        appendLine("path=$file")
        appendLine("exists=true file=${file.isFile()} directory=${file.isDirectory()}")
        appendLine(
            "read=${file.canRead()} write=${file.canWrite()} execute=${file.canExecute()} " +
                "symbolicLink=${file.isSymbolicLink()} hidden=${file.isHidden()}",
        )
        appendLine("length=${file.length()} lastModified=${file.lastModified()}")
        append(metadata.toDisplayText())
    }
}

private fun PrivilegeFileMetadata.toDisplayText(): String =
    "$absolutePath type=$type size=$sizeBytes mode=0${unixMode.toString(8)} uid=$uid gid=$gid"

private fun deleteIfPresent(file: PrivilegeFile): Boolean =
    !file.exists() || file.delete()

private const val DEFAULT_TEST_DIRECTORY = "/data/local/tmp/priv-kit-file-api"
private const val PAYLOAD_FILE_NAME = "payload.txt"
private const val RENAMED_FILE_NAME = "renamed.txt"
private const val SAMPLE_WALK_MAX_DEPTH = 8
private const val MAX_DISPLAYED_ENTRIES = 200
private const val MAX_OUTPUT_CHARS = 24_000
