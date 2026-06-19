package priv.kit.core

import android.util.Base64
import java.security.SecureRandom

object PrivilegeToken {
    private val secureRandom = SecureRandom()

    const val LENGTH = 16

    fun generate(): String {
        val bytes = ByteArray(12)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }

    fun isValid(value: String): Boolean =
        value.length == LENGTH && value.all { char ->
            char in 'A'..'Z' ||
                char in 'a'..'z' ||
                char in '0'..'9' ||
                char == '-' ||
                char == '_'
        }
}
