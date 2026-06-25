package priv.kit.runtime

import priv.kit.adb.PrivilegeAdbKeyBytes
import priv.kit.core.PrivilegeStartupException
import java.io.File

internal object PrivilegeAdbKeyStore {
    fun readOrCreate(): ByteArray =
        synchronized(lock) {
            cachedKeyBytes?.let { return@synchronized it.copyOf() }

            val file = adbKeyFile()
            val existingBytes = runCatching {
                PrivilegeBinaryFileStore.readIfExists(file)
            }.getOrElse { throwable ->
                throw PrivilegeStartupException("Failed to read ADB key file: ${file.absolutePath}", throwable)
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
            throw PrivilegeStartupException("Failed to validate ADB key bytes", throwable)
        }

    private fun create(): ByteArray =
        runCatching {
            PrivilegeAdbKeyBytes.create()
        }.getOrElse { throwable ->
            throw PrivilegeStartupException("Failed to create ADB key bytes", throwable)
        }

    private fun write(file: File, bytes: ByteArray) {
        val bytesToWrite = bytes.copyOf()
        runCatching {
            PrivilegeBinaryFileStore.writeAtomically(file, bytesToWrite)
        }.onFailure { throwable ->
            throw PrivilegeStartupException("Failed to write ADB key file: ${file.absolutePath}", throwable)
        }
    }

    private fun cache(bytes: ByteArray): ByteArray {
        val copy = bytes.copyOf()
        cachedKeyBytes = copy
        return copy.copyOf()
    }

    private fun adbKeyFile(): File {
        val context = PrivilegeRuntimeContext.require()
        return File(File(context.filesDir, STORAGE_DIRECTORY), KEY_FILE_NAME)
    }

    private const val STORAGE_DIRECTORY = ".priv-kit"
    private const val KEY_FILE_NAME = "adbkey"
    private val lock = Any()
    private var cachedKeyBytes: ByteArray? = null
}
