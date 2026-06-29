package priv.kit.adb

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object PrivilegeAdbKeyStore {
    private const val STORAGE_DIRECTORY = ".priv-kit"
    private const val KEY_FILE_NAME = "adbkey"

    private val lock = Any()
    private var cachedKeyBytes: ByteArray? = null

    fun readOrCreate(context: Context): ByteArray =
        synchronized(lock) {
            cachedKeyBytes?.let { return@synchronized it.copyOf() }

            val file = adbKeyFile(context)
            val existingBytes = runCatching {
                readIfExists(file)
            }.getOrElse { throwable ->
                throw PrivilegeAdbException("Failed to read ADB key file: ${file.absolutePath}", throwable)
            }

            val keyBytes = if (existingBytes != null && isReadable(existingBytes)) {
                existingBytes
            } else {
                create()
            }
            if (existingBytes == null || !existingBytes.contentEquals(keyBytes)) {
                write(file, keyBytes)
            }
            cache(keyBytes)
        }

    private fun isReadable(bytes: ByteArray): Boolean =
        runCatching {
            PrivilegeAdbKeyBytes.isReadable(bytes)
        }.getOrElse { throwable ->
            throw PrivilegeAdbException("Failed to validate ADB key bytes", throwable)
        }

    private fun create(): ByteArray =
        runCatching {
            PrivilegeAdbKeyBytes.create()
        }.getOrElse { throwable ->
            throw PrivilegeAdbException("Failed to create ADB key bytes", throwable)
        }

    private fun write(file: File, bytes: ByteArray) {
        val bytesToWrite = bytes.copyOf()
        runCatching {
            writeAtomically(file, bytesToWrite)
        }.onFailure { throwable ->
            throw PrivilegeAdbException("Failed to write ADB key file: ${file.absolutePath}", throwable)
        }
    }

    private fun cache(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        cachedKeyBytes = copy
        return copy.copyOf()
    }

    private fun adbKeyFile(context: Context): File =
        File(File(context.filesDir, STORAGE_DIRECTORY), KEY_FILE_NAME)

    private fun readIfExists(file: File): ByteArray? =
        if (file.isFile) file.readBytes() else null

    private fun writeAtomically(file: File, bytes: ByteArray) {
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
