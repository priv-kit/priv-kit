package priv.kit.core.file

import android.system.ErrnoException
import kotlinx.coroutines.flow.Flow
import priv.kit.core.internal.file.PrivilegeFileSystemClient
import priv.kit.core.internal.file.PrivilegeFileSystemContract
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * An absolute path whose filesystem operations execute in the connected Privileged Server.
 *
 * This is not a [java.io.File] subclass. Path composition and [isHidden] are local. Methods that
 * inspect or mutate the filesystem use synchronous Binder calls. They can run on any thread, but
 * should normally run on a worker thread while waiting for dispatch and server-side filesystem
 * I/O. Methods that share a name with [java.io.File] retain its return-value semantics. A missing
 * or dead server instead throws
 * [priv.kit.core.binder.PrivilegeServerUnavailableException].
 */
public class PrivilegeFile internal constructor(
    public val absolutePath: String,
    private val operations: PrivilegeFileOperations = PrivilegeFileSystemClient,
) {
    init {
        PrivilegeFilePath.validateAbsolute(absolutePath)
    }

    public val name: String
        get() = PrivilegeFilePath.name(absolutePath)

    public val parent: String?
        get() = PrivilegeFilePath.parent(absolutePath)

    public val parentFile: PrivilegeFile?
        get() = parent?.let { PrivilegeFile(it, operations) }

    /** Resolves a relative path without canonicalizing `.` or `..` segments. */
    public fun resolve(relativePath: String): PrivilegeFile {
        PrivilegeFilePath.validateRelative(relativePath)
        val separator = if (absolutePath.endsWith('/')) "" else "/"
        return PrivilegeFile("$absolutePath$separator$relativePath", operations)
    }

    public fun exists(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_EXISTS)

    public fun isFile(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_IS_FILE)

    public fun isDirectory(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_IS_DIRECTORY)

    public fun isHidden(): Boolean = name.startsWith('.')

    public fun isSymbolicLink(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_IS_SYMBOLIC_LINK)

    public fun canRead(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_CAN_READ)

    public fun canWrite(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_CAN_WRITE)

    public fun canExecute(): Boolean =
        operations.query(absolutePath, PrivilegeFileSystemContract.QUERY_CAN_EXECUTE)

    public fun length(): Long =
        operations.queryLong(absolutePath, PrivilegeFileSystemContract.QUERY_LENGTH)

    public fun lastModified(): Long =
        operations.queryLong(absolutePath, PrivilegeFileSystemContract.QUERY_LAST_MODIFIED)

    /** Reads one metadata snapshot. Symbolic links are not followed by default. */
    @Throws(ErrnoException::class)
    public fun metadata(
        followSymbolicLinks: Boolean = false,
    ): PrivilegeFileMetadata = operations.metadata(absolutePath, followSymbolicLinks)

    @Throws(IOException::class)
    public fun createNewFile(): Boolean = operations.createNewFile(absolutePath)

    public fun mkdir(): Boolean = operations.mkdir(absolutePath)

    public fun mkdirs(): Boolean = operations.mkdirs(absolutePath)

    public fun delete(): Boolean = operations.delete(absolutePath)

    public fun renameTo(destination: PrivilegeFile): Boolean =
        operations.renameTo(absolutePath, destination.absolutePath)

    /**
     * Atomically renames this filesystem entry over [destination].
     *
     * Both paths must be on the same mounted filesystem. The operation directly uses Linux
     * `rename(2)` in the Privileged Server and never falls back to copying or deleting the
     * destination first. This method does not synchronize file data or the parent directory.
     *
     * @throws IOException if the atomic replacement fails. Its cause is an [ErrnoException]
     * carrying the Linux error, such as `EXDEV` for paths on different mounted filesystems.
     */
    @Throws(IOException::class)
    public fun replaceAtomically(destination: PrivilegeFile) {
        operations.replaceAtomically(absolutePath, destination.absolutePath)
    }

    /** Opens a pipe-backed stream whose source file remains owned by the Privileged Server. */
    @Throws(IOException::class)
    public fun openInputStream(): InputStream = operations.openInputStream(absolutePath)

    /**
     * Opens a pipe-backed stream whose destination file remains owned by the Privileged Server.
     *
     * Closing the stream waits until the server has consumed all bytes and closed the destination.
     * When [syncOnClose] is true, the server also calls `fsync(2)` before reporting completion.
     */
    @Throws(IOException::class)
    public fun openOutputStream(
        append: Boolean = false,
        syncOnClose: Boolean = false,
    ): OutputStream =
        operations.openOutputStream(
            path = absolutePath,
            append = append,
            syncOnClose = syncOnClose,
        )

    /**
     * Streams an unsorted, non-recursive and weakly-consistent directory scan.
     *
     * Each collection starts a new scan. An entry can have null
     * [PrivilegeFileDirectoryEntry.metadata] when its name is enumerable but the server identity
     * cannot read its attributes. Cancelling collection closes its pipe and stops the corresponding
     * server-side writer. Binder setup and pipe reading run on [kotlinx.coroutines.Dispatchers.IO].
     */
    public fun scanDirectory(): Flow<PrivilegeFileDirectoryEntry> =
        operations.scanDirectory(absolutePath)

    override fun equals(other: Any?): Boolean =
        other is PrivilegeFile && absolutePath == other.absolutePath

    override fun hashCode(): Int = absolutePath.hashCode()

    override fun toString(): String = absolutePath
}

internal object PrivilegeFilePath {
    private const val MAX_PATH_UTF8_BYTES: Int = 4_095

    fun validateAbsolute(path: String) {
        require(path.startsWith('/')) { "PrivilegeFile path must be absolute: $path" }
        validateCommon(path)
    }

    fun validateRelative(path: String) {
        require(path.isNotEmpty()) { "Relative path must not be empty" }
        require(!path.startsWith('/')) { "Path must be relative: $path" }
        require(path.length <= MAX_PATH_UTF8_BYTES) {
            "Path exceeds $MAX_PATH_UTF8_BYTES UTF-8 bytes"
        }
    }

    fun name(path: String): String {
        if (path == "/") return ""
        val end = path.indexOfLast { it != '/' } + 1
        if (end <= 0) return ""
        return path.substring(0, end).substringAfterLast('/')
    }

    fun parent(path: String): String? {
        if (path == "/") return null
        val end = path.indexOfLast { it != '/' } + 1
        if (end <= 1) return "/"
        val separator = path.lastIndexOf('/', end - 2)
        return if (separator <= 0) "/" else path.substring(0, separator)
    }

    private fun validateCommon(path: String) {
        require('\u0000' !in path) { "Path must not contain NUL" }
        require(path.length <= MAX_PATH_UTF8_BYTES) {
            "Path exceeds $MAX_PATH_UTF8_BYTES UTF-8 bytes"
        }
        if (path.any { it.code > ASCII_MAX_CODE_POINT }) {
            require(path.toByteArray(Charsets.UTF_8).size <= MAX_PATH_UTF8_BYTES) {
                "Path exceeds $MAX_PATH_UTF8_BYTES UTF-8 bytes"
            }
        }
    }

    private const val ASCII_MAX_CODE_POINT: Int = 0x7f
}
