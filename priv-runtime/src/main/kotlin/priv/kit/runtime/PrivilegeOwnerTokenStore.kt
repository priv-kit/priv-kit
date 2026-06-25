package priv.kit.runtime

import android.util.Base64
import priv.kit.core.PrivilegeStartupException
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

internal object PrivilegeOwnerTokenStore {
    fun readOrCreate(): String =
        synchronized(lock) {
            val file = ownerTokenFile()
            if (file.isFile) {
                return@synchronized readExisting(file)
            }

            val token = generateToken()
            writeNew(file, token)
            token
        }

    fun readIfExists(): String? =
        synchronized(lock) {
            val file = ownerTokenFile()
            if (file.isFile) readExisting(file) else null
        }

    private fun readExisting(file: File): String {
        val token = runCatching {
            file.readText(StandardCharsets.UTF_8).trim()
        }.getOrElse { throwable ->
            throw PrivilegeStartupException("Failed to read owner token file: ${file.absolutePath}", throwable)
        }
        if (token.isBlank()) {
            throw PrivilegeStartupException("Owner token file is empty: ${file.absolutePath}")
        }
        return token
    }

    private fun writeNew(file: File, token: String) {
        val directory = file.parentFile
            ?: throw PrivilegeStartupException("Owner token file has no parent directory: ${file.absolutePath}")
        if (!directory.exists() && !directory.mkdirs()) {
            throw PrivilegeStartupException("Failed to create owner token directory: ${directory.absolutePath}")
        }

        val temporaryFile = File(directory, "${file.name}.tmp")
        runCatching {
            FileOutputStream(temporaryFile).use { output ->
                output.write(token.toByteArray(StandardCharsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            if (!temporaryFile.renameTo(file)) {
                throw IllegalStateException("Failed to commit owner token file")
            }
        }.onFailure { throwable ->
            temporaryFile.delete()
            throw PrivilegeStartupException("Failed to create owner token file: ${file.absolutePath}", throwable)
        }
    }

    private fun ownerTokenFile(): File {
        val context = PrivilegeRuntimeContext.require()
        return File(File(context.filesDir, OWNER_TOKEN_DIRECTORY), OWNER_TOKEN_FILE)
    }

    private const val OWNER_TOKEN_DIRECTORY = ".priv-kit"
    private const val OWNER_TOKEN_FILE = "token.txt"
    private const val TOKEN_BYTE_LENGTH = 12
    private val lock = Any()

    private fun generateToken(): String {
        val bytes = ByteArray(TOKEN_BYTE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }
}
