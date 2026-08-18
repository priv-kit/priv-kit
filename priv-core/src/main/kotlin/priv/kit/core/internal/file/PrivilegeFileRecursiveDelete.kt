package priv.kit.core.internal.file

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

internal object PrivilegeFileRecursiveDelete {
    suspend fun delete(path: String): Boolean {
        currentCoroutineContext().ensureActive()
        val target = Paths.get(path)
        val targetAttributes = try {
            Files.readAttributes(
                target,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
        if (!targetAttributes.isDirectory) return deleteLeaf(target)

        val parentPath = target.parent ?: return false
        val targetName = target.fileName ?: return false
        val parentStream = try {
            Files.newDirectoryStream(parentPath)
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: NotDirectoryException) {
            return true
        } catch (_: IOException) {
            return false
        } catch (_: SecurityException) {
            return false
        }

        if (parentStream !is SecureDirectoryStream<*>) {
            runCatching(parentStream::close)
            return false
        }

        @Suppress("UNCHECKED_CAST")
        val secureParent = parentStream as SecureDirectoryStream<Path>
        return secureParent.use { parent ->
            deleteEntry(parent, targetName)
        }
    }

    private fun deleteLeaf(path: Path): Boolean =
        try {
            Files.deleteIfExists(path)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private suspend fun deleteEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): Boolean {
        currentCoroutineContext().ensureActive()
        return when (entryKind(parent, name)) {
            EntryKind.MISSING -> true
            EntryKind.FAILED -> false
            EntryKind.OTHER -> deleteUnknownEntry(parent, name)
            EntryKind.DIRECTORY -> {
                val stream = openDirectory(parent, name)
                if (stream == null) {
                    deleteUnknownEntry(parent, name)
                } else {
                    deleteDirectoryTree(parent, name, stream)
                }
            }
        }
    }

    private suspend fun deleteDirectoryTree(
        rootParent: SecureDirectoryStream<Path>,
        rootName: Path,
        rootStream: SecureDirectoryStream<Path>,
    ): Boolean {
        val frames = ArrayDeque<DirectoryFrame>()
        frames.addLast(DirectoryFrame(rootParent, rootName, rootStream))
        var succeeded = true
        try {
            while (frames.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val frame = requireNotNull(frames.peekLast())
                val child = try {
                    if (frame.iterator.hasNext()) frame.iterator.next() else null
                } catch (_: DirectoryIteratorException) {
                    succeeded = false
                    null
                } catch (_: SecurityException) {
                    succeeded = false
                    null
                }

                if (child == null) {
                    frames.removeLast()
                    if (!closeDirectory(frame.stream)) succeeded = false
                    currentCoroutineContext().ensureActive()
                    if (!deleteDirectoryOrReplacement(frame.parent, frame.name)) {
                        succeeded = false
                    }
                    continue
                }

                val childName = child.fileName ?: continue
                when (entryKind(frame.stream, childName)) {
                    EntryKind.MISSING -> Unit
                    EntryKind.FAILED -> succeeded = false
                    EntryKind.OTHER -> {
                        if (!deleteUnknownEntry(frame.stream, childName)) succeeded = false
                    }
                    EntryKind.DIRECTORY -> {
                        val childStream = openDirectory(frame.stream, childName)
                        if (childStream == null) {
                            if (!deleteUnknownEntry(frame.stream, childName)) succeeded = false
                        } else {
                            frames.addLast(
                                DirectoryFrame(
                                    parent = frame.stream,
                                    name = childName,
                                    stream = childStream,
                                ),
                            )
                        }
                    }
                }
            }
        } finally {
            while (frames.isNotEmpty()) {
                closeDirectory(frames.removeLast().stream)
            }
        }
        return succeeded
    }

    private fun entryKind(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): EntryKind = try {
        val view = parent.getFileAttributeView(
            name,
            BasicFileAttributeView::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ) ?: return EntryKind.FAILED
        if (view.readAttributes().isDirectory) EntryKind.DIRECTORY else EntryKind.OTHER
    } catch (_: NoSuchFileException) {
        EntryKind.MISSING
    } catch (_: IOException) {
        EntryKind.FAILED
    } catch (_: SecurityException) {
        EntryKind.FAILED
    }

    private fun openDirectory(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): SecureDirectoryStream<Path>? = try {
        parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private fun deleteUnknownEntry(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): Boolean {
        try {
            parent.deleteFile(name)
            return true
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: IOException) {
        } catch (_: SecurityException) {
            return false
        }

        return try {
            parent.deleteDirectory(name)
            true
        } catch (_: NoSuchFileException) {
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun deleteDirectoryOrReplacement(
        parent: SecureDirectoryStream<Path>,
        name: Path,
    ): Boolean {
        try {
            parent.deleteDirectory(name)
            return true
        } catch (_: NoSuchFileException) {
            return true
        } catch (_: IOException) {
        } catch (_: SecurityException) {
            return false
        }
        return deleteUnknownEntry(parent, name)
    }

    private fun closeDirectory(stream: SecureDirectoryStream<Path>): Boolean =
        try {
            stream.close()
            true
        } catch (_: IOException) {
            false
        }

    private enum class EntryKind {
        DIRECTORY,
        OTHER,
        MISSING,
        FAILED,
    }

    private class DirectoryFrame(
        val parent: SecureDirectoryStream<Path>,
        val name: Path,
        val stream: SecureDirectoryStream<Path>,
    ) {
        val iterator: Iterator<Path> = stream.iterator()
    }
}
