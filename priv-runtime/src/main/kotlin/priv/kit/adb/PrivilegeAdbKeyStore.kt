package priv.kit.adb

import java.io.File
import priv.kit.internal.runtime.PrivilegeBinaryFileStore
import priv.kit.internal.runtime.PrivilegeStorage

internal object PrivilegeAdbKeyStore {
    private const val KEY_FILE_NAME = "adbkey"

    private val lock = Any()
    private var cachedKeyBytes: ByteArray? = null

    fun readOrCreate(): ByteArray =
        synchronized(lock) {
            cachedKeyBytes?.let { return@synchronized it.copyOf() }

            val file = adbKeyFile()
            val existingBytes = runCatching {
                PrivilegeBinaryFileStore.readIfExists(file)
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
            PrivilegeBinaryFileStore.writeAtomically(file, bytesToWrite)
        }.onFailure { throwable ->
            throw PrivilegeAdbException("Failed to write ADB key file: ${file.absolutePath}", throwable)
        }
    }

    private fun cache(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        cachedKeyBytes = copy
        return copy.copyOf()
    }

    private fun adbKeyFile(): File =
        PrivilegeStorage.file(KEY_FILE_NAME)
}
