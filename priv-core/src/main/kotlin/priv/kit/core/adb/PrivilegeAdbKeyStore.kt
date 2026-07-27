package priv.kit.core.adb

import java.io.File
import priv.kit.core.internal.runtime.PrivilegeStorage
import priv.kit.shared.PrivilegeBinaryFileStore

internal object PrivilegeAdbKeyStore {
    private const val KEY_FILE_NAME = "adbkey"

    private val keyMaterial by lazy {
        PrivilegeAdbKeyMaterial(readOrCreateKeyBytes())
    }

    fun readOrCreateKey(adbDeviceName: String): PrivilegeAdbKey =
        PrivilegeAdbKey(
            material = keyMaterial,
            name = adbDeviceName,
        )

    private fun readOrCreateKeyBytes(): ByteArray {
        val file = PrivilegeStorage.file(KEY_FILE_NAME)
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
        return keyBytes
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
}
