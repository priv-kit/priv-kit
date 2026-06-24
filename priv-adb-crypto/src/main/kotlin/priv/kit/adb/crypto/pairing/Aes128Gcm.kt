package priv.kit.adb.crypto.pairing

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ADB pairing_auth uses this exact HKDF info string for AES-128-GCM key derivation.
private val ADB_PAIRING_AES_128_GCM_KEY_INFO =
    "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_KEY_ALGORITHM = "AES"
private const val AES_128_KEY_SIZE_BYTES = 16
private const val GCM_TAG_SIZE_BITS = 128
private const val ADB_PAIRING_NONCE_SIZE_BYTES = 12

internal class Aes128Gcm(keyMaterial: ByteArray) {
    private val key = Hkdf.sha256(
        inputKeyMaterial = keyMaterial,
        info = ADB_PAIRING_AES_128_GCM_KEY_INFO,
        outputSize = AES_128_KEY_SIZE_BYTES,
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
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                mode,
                SecretKeySpec(key, AES_KEY_ALGORITHM),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, nonce),
            )
            cipher.doFinal(input)
        } catch (_: AEADBadTagException) {
            null
        } catch (_: RuntimeException) {
            null
        }

    private fun nonce(sequence: Long): ByteArray {
        val nonce = ByteArray(ADB_PAIRING_NONCE_SIZE_BYTES)
        for (index in 0 until Long.SIZE_BYTES) {
            nonce[index] = (sequence ushr (index * 8)).toByte()
        }
        return nonce
    }
}
