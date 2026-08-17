package priv.kit.core.internal.file

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import priv.kit.core.Privilege
import priv.kit.core.file.PrivilegeFileDirectoryEntry
import priv.kit.core.file.PrivilegeFileMetadata
import priv.kit.core.file.PrivilegeFileOperations
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal object PrivilegeFileSystemClient : PrivilegeFileOperations {
    override fun query(path: String, kind: Int): Boolean =
        call { fileSystem -> fileSystem.query(path, kind) }

    override fun queryLong(path: String, kind: Int): Long =
        call { fileSystem -> fileSystem.queryLong(path, kind) }

    override fun metadata(
        path: String,
        followSymbolicLinks: Boolean,
    ): PrivilegeFileMetadata {
        val result = call { fileSystem ->
            fileSystem.stat(path, followSymbolicLinks)
        }
        result.throwIfFailed("stat($path)")
        val values = result.values
            ?: error("Privileged Server returned no metadata for $path")
        return PrivilegeFileWire.metadataFromArray(path, values)
    }

    override fun openInputStream(path: String): InputStream {
        val result = call { fileSystem -> fileSystem.openInput(path) }
        if (result.errno != 0) {
            throw IOException(
                "Unable to open $path for reading",
                errnoException("openInput($path)", result.errno),
            )
        }
        result.completionDescriptor?.close()
        val descriptor = result.fileDescriptor
            ?: throw IOException("Privileged Server returned no input pipe for $path")
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    }

    override fun openOutputStream(
        path: String,
        append: Boolean,
        syncOnClose: Boolean,
    ): OutputStream {
        val result = call { fileSystem ->
            fileSystem.openOutput(path, append, syncOnClose)
        }
        if (result.errno != 0) {
            throw IOException(
                "Unable to open $path for writing",
                errnoException("openOutput($path)", result.errno),
            )
        }
        val descriptor = result.fileDescriptor
        val completionDescriptor = result.completionDescriptor
        if (descriptor == null || completionDescriptor == null) {
            runCatching { descriptor?.close() }
            runCatching { completionDescriptor?.close() }
            throw IOException("Privileged Server returned an incomplete output pipe for $path")
        }
        return CompletingOutputStream(path, descriptor, completionDescriptor)
    }

    override fun createNewFile(path: String): Boolean {
        val result = call { fileSystem -> fileSystem.createNewFile(path) }
        if (result < 0) {
            throw IOException(
                "Unable to create $path",
                errnoException("createNewFile($path)", -result),
            )
        }
        return result != 0
    }

    override fun mkdir(path: String): Boolean =
        call { fileSystem -> fileSystem.mkdir(path) }

    override fun mkdirs(path: String): Boolean =
        call { fileSystem -> fileSystem.mkdirs(path) }

    override fun delete(path: String): Boolean =
        call { fileSystem -> fileSystem.delete(path) }

    override fun renameTo(sourcePath: String, targetPath: String): Boolean =
        call { fileSystem -> fileSystem.renameTo(sourcePath, targetPath) }

    override fun replaceAtomically(sourcePath: String, targetPath: String) {
        val errno = call { fileSystem ->
            fileSystem.replaceAtomically(sourcePath, targetPath)
        }
        if (errno != 0) {
            throw IOException(
                "Unable to atomically replace $targetPath with $sourcePath",
                errnoException("replaceAtomically($sourcePath, $targetPath)", errno),
            )
        }
    }

    override fun scanDirectory(path: String): Flow<PrivilegeFileDirectoryEntry> = callbackFlow {
        val pipe = ParcelFileDescriptor.createPipe()
        val source = pipe[0]
        val sink = pipe[1]
        val reader = launch(Dispatchers.IO) {
            try {
                sink.use { sink ->
                    val errno = call { fileSystem -> fileSystem.scanDirectory(path, sink) }
                    if (errno != 0) throw errnoException("scanDirectory($path)", errno)
                }

                DataInputStream(
                    BufferedInputStream(ParcelFileDescriptor.AutoCloseInputStream(source)),
                ).use { input ->
                    while (true) {
                        when (val frame = input.readUnsignedByte()) {
                            PrivilegeFileSystemContract.SCAN_ENTRY ->
                                send(PrivilegeFileWire.readEntry(input))

                            PrivilegeFileSystemContract.SCAN_COMPLETE -> {
                                close()
                                return@launch
                            }
                            PrivilegeFileSystemContract.SCAN_ERROR -> {
                                val errno = input.readInt()
                                val message = input.readUTF()
                                throw ErrnoException(
                                    "scanDirectory($path): $message",
                                    errno,
                                )
                            }

                            else -> throw IOException("Unknown directory scan frame: $frame")
                        }
                    }
                }
            } catch (exception: EOFException) {
                close(IOException("Directory scan ended before completion: $path", exception))
            } catch (_: CancellationException) {
            } catch (throwable: Throwable) {
                close(throwable)
            } finally {
                runCatching(source::close)
                runCatching(sink::close)
            }
        }
        awaitClose {
            runCatching(source::close)
            runCatching(sink::close)
            reader.cancel()
        }
    }

    private fun <T> call(block: (IPrivilegeFileSystem) -> T): T =
        Privilege.callFileSystem(block)

    private fun PrivilegeFileResult.throwIfFailed(functionName: String) {
        if (errno != 0) throw errnoException(functionName, errno)
    }

    private fun errnoException(functionName: String, errno: Int): ErrnoException =
        ErrnoException(functionName, errno)

    private class CompletingOutputStream(
        private val path: String,
        descriptor: ParcelFileDescriptor,
        completionDescriptor: ParcelFileDescriptor,
    ) : OutputStream() {
        private val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        private val completion = DataInputStream(
            BufferedInputStream(
                ParcelFileDescriptor.AutoCloseInputStream(completionDescriptor),
            ),
        )
        private var closed = false

        override fun write(value: Int) {
            output.write(value)
        }

        override fun write(buffer: ByteArray) {
            output.write(buffer)
        }

        override fun write(buffer: ByteArray, offset: Int, byteCount: Int) {
            output.write(buffer, offset, byteCount)
        }

        override fun flush() {
            output.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            var failure: IOException? = null
            try {
                output.close()
            } catch (exception: IOException) {
                failure = exception
            }
            try {
                val errno = completion.readInt()
                val message = completion.readUTF()
                if (errno != 0) {
                    throw IOException(
                        message.ifBlank { "Unable to finish writing $path" },
                        errnoException("write($path)", errno),
                    )
                }
            } catch (exception: EOFException) {
                val incomplete = IOException(
                    "Privileged Server ended the write before reporting completion: $path",
                    exception,
                )
                failure = failure.withSuppressed(incomplete)
            } catch (exception: IOException) {
                failure = failure.withSuppressed(exception)
            } finally {
                try {
                    completion.close()
                } catch (exception: IOException) {
                    failure = failure.withSuppressed(exception)
                }
            }
            failure?.let { throw it }
        }

        private fun IOException?.withSuppressed(next: IOException): IOException =
            this?.apply { addSuppressed(next) } ?: next
    }
}
