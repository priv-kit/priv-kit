package priv.kit.ssl.internal

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class Aes128Gcm(keyMaterial: ByteArray) {
    private val key = Hkdf.sha256(
        inputKeyMaterial = keyMaterial,
        info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
        outputSize = 16,
    )
    private var encSequence = 0L
    private var decSequence = 0L

    fun encrypt(input: ByteArray): ByteArray? =
        runCipher(Cipher.ENCRYPT_MODE, input, nonce(encSequence))?.also {
            encSequence++
        }

    fun decrypt(input: ByteArray): ByteArray? =
        runCipher(Cipher.DECRYPT_MODE, input, nonce(decSequence))?.also {
            decSequence++
        }

    private fun runCipher(mode: Int, input: ByteArray, nonce: ByteArray): ByteArray? =
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.doFinal(input)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun nonce(sequence: Long): ByteArray {
        val nonce = ByteArray(12)
        for (index in 0 until Long.SIZE_BYTES) {
            nonce[index] = (sequence ushr (index * 8)).toByte()
        }
        return nonce
    }
}
