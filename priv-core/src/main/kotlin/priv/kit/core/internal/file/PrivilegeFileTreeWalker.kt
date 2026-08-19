package priv.kit.core.internal.file

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream

internal object PrivilegeFileTreeWalker {
    suspend fun write(
        path: String,
        maxDepth: Int,
        sink: ParcelFileDescriptor,
    ) {
        val output = DataOutputStream(
            BufferedOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(sink)),
        )
        try {
            walk(path, maxDepth).collect { entry ->
                PrivilegeFileWire.writeEntry(
                    output = output,
                    path = entry.absolutePath,
                    depth = entry.depth,
                    stat = entry.stat,
                )
                output.flush()
            }
            output.writeByte(PrivilegeFileSystemContract.WALK_COMPLETE)
            output.flush()
        } catch (exception: CancellationException) {
            throw exception
        } catch (throwable: Throwable) {
            val failure = throwable.toWalkFailure()
            runCatching {
                output.writeByte(PrivilegeFileSystemContract.WALK_ERROR)
                output.writeInt(failure.errno)
                output.writeUTF(failure.message)
                output.flush()
            }
        } finally {
            runCatching(output::close)
        }
    }

    fun walk(path: String, maxDepth: Int): Flow<PrivilegeFileWalkRecord> =
        PrivilegeFileDepthFirstWalk.walk(
            maxDepth = maxDepth,
            openRoot = { openWalkRoot(path) },
            nextNode = WalkDirectory::next,
            isDirectory = { node ->
                node.stat != null && OsConstants.S_ISDIR(node.stat.st_mode)
            },
            openDirectory = { directory, node -> directory.open(node) },
        ).map { entry ->
            PrivilegeFileWalkRecord(
                absolutePath = entry.node.absolutePath.toString(),
                depth = entry.depth,
                stat = entry.node.stat,
            )
        }

    private fun openWalkRoot(path: String): WalkDirectory {
        val rootStat = Os.lstat(path)
        if (!OsConstants.S_ISDIR(rootStat.st_mode)) {
            throw ErrnoException("walk($path)", OsConstants.ENOTDIR)
        }
        val rootPath = File(path).toPath()
        return WalkDirectory(rootPath, openRoot(rootPath))
    }

    private class WalkDirectory(
        private val absolutePath: Path,
        private val stream: SecureDirectoryStream<Path>,
    ) : java.io.Closeable {
        private val iterator: Iterator<Path> = stream.iterator()

        fun next(): WalkNode? {
            while (iterator.hasNext()) {
                val childName = iterator.next().fileName ?: continue
                val childPath = absolutePath.resolve(childName)
                val stat = try {
                    Os.lstat(childPath.toString())
                } catch (exception: ErrnoException) {
                    when (
                        classifyPrivilegeFileWalkStatFailure(
                            errno = exception.errno,
                            noEntryErrno = OsConstants.ENOENT,
                            accessDeniedErrno = OsConstants.EACCES,
                            operationNotPermittedErrno = OsConstants.EPERM,
                        )
                    ) {
                        PrivilegeFileWalkStatFailureAction.SKIP_ENTRY -> continue
                        PrivilegeFileWalkStatFailureAction.EMIT_NAME_ONLY -> null
                        PrivilegeFileWalkStatFailureAction.FAIL_WALK -> throw exception
                    }
                }
                return WalkNode(childName, childPath, stat)
            }
            return null
        }

        fun open(node: WalkNode): WalkDirectory = WalkDirectory(
            absolutePath = node.absolutePath,
            stream = stream.newDirectoryStream(node.name, LinkOption.NOFOLLOW_LINKS),
        )

        override fun close() {
            stream.close()
        }
    }

    private fun openRoot(path: Path): SecureDirectoryStream<Path> {
        val parentPath = path.parent
        val targetName = path.fileName
        if (parentPath != null && targetName != null) {
            val parent = asSecureDirectoryStream(parentPath, Files.newDirectoryStream(parentPath))
            return parent.use {
                it.newDirectoryStream(targetName, LinkOption.NOFOLLOW_LINKS)
            }
        }
        return asSecureDirectoryStream(path, Files.newDirectoryStream(path))
    }

    private fun asSecureDirectoryStream(
        path: Path,
        stream: java.nio.file.DirectoryStream<Path>,
    ): SecureDirectoryStream<Path> {
        if (stream !is SecureDirectoryStream<*>) {
            runCatching(stream::close)
            throw ErrnoException("walk($path)", OsConstants.EOPNOTSUPP)
        }
        @Suppress("UNCHECKED_CAST")
        return stream as SecureDirectoryStream<Path>
    }

    private fun Throwable.toWalkFailure(): WalkFailure {
        val actual = if (this is DirectoryIteratorException) cause ?: this else this
        return when (actual) {
            is ErrnoException -> WalkFailure(actual.errno, actual.walkMessage())
            is NoSuchFileException -> WalkFailure(OsConstants.ENOENT, actual.walkMessage())
            is NotDirectoryException -> WalkFailure(OsConstants.ENOTDIR, actual.walkMessage())
            is AccessDeniedException,
            is SecurityException,
            -> WalkFailure(OsConstants.EACCES, actual.walkMessage())
            is IOException -> WalkFailure(OsConstants.EIO, actual.walkMessage())
            else -> WalkFailure(OsConstants.EIO, actual.walkMessage())
        }
    }

    private fun Throwable.walkMessage(): String =
        message.orEmpty().take(MAX_WALK_ERROR_CHARS).ifBlank { javaClass.simpleName }

    private data class WalkNode(
        val name: Path,
        val absolutePath: Path,
        val stat: StructStat?,
    )

    private data class WalkFailure(
        val errno: Int,
        val message: String,
    )

    private const val MAX_WALK_ERROR_CHARS: Int = 2_048
}

internal data class PrivilegeFileWalkRecord(
    val absolutePath: String,
    val depth: Int,
    val stat: StructStat?,
)
