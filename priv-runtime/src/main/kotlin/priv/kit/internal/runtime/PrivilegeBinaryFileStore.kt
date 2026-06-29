package priv.kit.internal.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object PrivilegeBinaryFileStore {
    fun readIfExists(file: File): ByteArray? =
        if (file.isFile) file.readBytes() else null

    fun read(file: File): ByteArray =
        file.readBytes()

    fun writeAtomically(file: File, bytes: ByteArray) {
        val directory = file.parentFile
            ?: throw IOException("File has no parent directory: ${file.absolutePath}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Failed to create directory: ${directory.absolutePath}")
        }

        val temporaryFile = File(directory, "${file.name}.tmp")
        try {
            FileOutputStream(temporaryFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            moveFile(temporaryFile, file)
        } catch (throwable: Throwable) {
            temporaryFile.delete()
            throw throwable
        }
    }

    private fun moveFile(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
