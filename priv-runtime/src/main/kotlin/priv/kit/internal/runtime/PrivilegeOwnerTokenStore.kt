package priv.kit.internal.runtime

import android.util.Base64
import priv.kit.PrivilegeStartupException
import priv.kit.shared.PrivilegeBinaryFileStore
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

internal object PrivilegeOwnerTokenStore {
    fun readOrCreate(): String =
        synchronized(lock) {
            readCachedOrExisting()?.let { return@synchronized it }

            val file = ownerTokenFile()
            val token = generateToken()
            writeNew(file, token)
            cache(token)
        }

    fun readIfExists(): String? =
        synchronized(lock) { readCachedOrExisting() }

    private fun readCachedOrExisting(): String? {
        cachedToken?.let { return it }

        val file = ownerTokenFile()
        return if (file.isFile) cache(readExisting(file)) else null
    }

    private fun cache(token: String): String {
        cachedToken = token
        return token
    }

    private fun readExisting(file: File): String {
        val token = runCatching {
            String(file.readBytes(), StandardCharsets.UTF_8).trim()
        }.getOrElse { throwable ->
            throw PrivilegeStartupException("Failed to read owner token file: ${file.absolutePath}", throwable)
        }
        if (token.isBlank()) {
            throw PrivilegeStartupException("Owner token file is empty: ${file.absolutePath}")
        }
        return token
    }

    private fun writeNew(file: File, token: String) {
        runCatching {
            PrivilegeBinaryFileStore.writeAtomically(
                file = file,
                bytes = "$token\n".toByteArray(StandardCharsets.UTF_8),
            )
        }.onFailure { throwable ->
            throw PrivilegeStartupException("Failed to create owner token file: ${file.absolutePath}", throwable)
        }
    }

    private fun ownerTokenFile(): File {
        return PrivilegeStorage.file(OWNER_TOKEN_FILE)
    }

    private const val OWNER_TOKEN_FILE = "token.txt"
    private const val TOKEN_BYTE_LENGTH = 12
    private val lock = Any()
    private var cachedToken: String? = null

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }
}
