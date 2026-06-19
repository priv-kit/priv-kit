package priv.kit.core

import android.util.Base64
import java.security.SecureRandom

object PrivilegeToken {
    private val secureRandom = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(
            bytes,
            Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE,
        )
    }
}
