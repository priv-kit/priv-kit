package priv.kit.adb.crypto.pairing

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Aes128GcmTest {
    @Test
    fun encryptMatchesBouncyCastleForSequentialAdbNonces() {
        val keyMaterial = Ed25519.hex(
            "d873c501e8d2b18dbf61efef24c96d729118c3b7fd6f314d5c996ecf7a4f9d52" +
                "eb320fdc1075a33f6e5a591426d4e67a83f747a6176b182bc3b7f05e6d",
        )
        val cipher = Aes128Gcm(keyMaterial)
        val firstMessage = byteArrayOf(0x2a, 0x2b, 0x2c)
        val secondMessage = byteArrayOf(0xff.toByte(), 0x45, 0x12, 0x33, 0x01)
        val key = adbAesKey(keyMaterial)

        assertArrayEquals(
            bouncyCastleAesGcm(true, key, adbNonce(0), firstMessage),
            cipher.encrypt(firstMessage),
        )
        assertArrayEquals(
            bouncyCastleAesGcm(true, key, adbNonce(1), secondMessage),
            cipher.encrypt(secondMessage),
        )
    }

    @Test
    fun decryptMatchesBouncyCastleAndRejectsModifiedTag() {
        val keyMaterial = Ed25519.hex(
            "2fd1428bd3c7ad1010d55561afe8cb76eeda552f3671fd8ae0b2433778712ab9" +
                "115f66bf60be17a27f147fb07188372dc5a50e24e7efb3310673bf244eca",
        )
        val message = byteArrayOf(0x10, 0x20, 0x30, 0x40)
        val key = adbAesKey(keyMaterial)
        val encrypted = bouncyCastleAesGcm(true, key, adbNonce(0), message)
        val cipher = Aes128Gcm(keyMaterial)

        assertArrayEquals(message, cipher.decrypt(encrypted))
        assertArrayEquals(message, bouncyCastleAesGcm(false, key, adbNonce(0), encrypted))

        val modified = encrypted.copyOf()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        assertNull(Aes128Gcm(keyMaterial).decrypt(modified))
    }

    @Test
    fun encryptedSizeIncludesDefaultGcmTag() {
        val keyMaterial = ByteArray(64) { it.toByte() }

        assertEquals(16, checkNotNull(Aes128Gcm(keyMaterial).encrypt(ByteArray(0))).size)
        assertEquals(19, checkNotNull(Aes128Gcm(keyMaterial).encrypt(byteArrayOf(0x01, 0x02, 0x03))).size)
    }

    private fun adbAesKey(keyMaterial: ByteArray): ByteArray =
        bouncyCastleHkdfSha256(
            inputKeyMaterial = keyMaterial,
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
            outputSize = 16,
        )

    private fun bouncyCastleHkdfSha256(
        inputKeyMaterial: ByteArray,
        info: ByteArray,
        outputSize: Int,
    ): ByteArray {
        val generator = org.bouncycastle.crypto.generators.HKDFBytesGenerator(
            org.bouncycastle.crypto.digests.SHA256Digest(),
        )
        generator.init(org.bouncycastle.crypto.params.HKDFParameters(inputKeyMaterial, ByteArray(0), info))
        val output = ByteArray(outputSize)
        generator.generateBytes(output, 0, output.size)
        return output
    }

    private fun bouncyCastleAesGcm(
        encrypt: Boolean,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
    ): ByteArray {
        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        cipher.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce))
        val output = ByteArray(cipher.getOutputSize(input.size))
        var size = cipher.processBytes(input, 0, input.size, output, 0)
        try {
            size += cipher.doFinal(output, size)
        } catch (exception: InvalidCipherTextException) {
            throw AssertionError("Bouncy Castle AES-GCM oracle failed", exception)
        }
        return output.copyOf(size)
    }

    private fun adbNonce(sequence: Long): ByteArray =
        ByteArray(12).also { nonce ->
            for (index in 0 until Long.SIZE_BYTES) {
                nonce[index] = (sequence ushr (index * 8)).toByte()
            }
        }
}
