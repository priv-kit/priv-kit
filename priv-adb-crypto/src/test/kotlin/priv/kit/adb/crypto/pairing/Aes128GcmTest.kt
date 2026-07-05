package priv.kit.adb.crypto.pairing

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
            BouncyCastlePairingOracle.aesGcm(
                encrypt = true,
                key = key,
                nonce = BouncyCastlePairingOracle.adbNonce(0),
                input = firstMessage,
            ),
            cipher.encrypt(firstMessage),
        )
        assertArrayEquals(
            BouncyCastlePairingOracle.aesGcm(
                encrypt = true,
                key = key,
                nonce = BouncyCastlePairingOracle.adbNonce(1),
                input = secondMessage,
            ),
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
        val encrypted = BouncyCastlePairingOracle.aesGcm(
            encrypt = true,
            key = key,
            nonce = BouncyCastlePairingOracle.adbNonce(0),
            input = message,
        )
        val cipher = Aes128Gcm(keyMaterial)

        assertArrayEquals(message, cipher.decrypt(encrypted))
        assertArrayEquals(
            message,
            BouncyCastlePairingOracle.aesGcm(
                encrypt = false,
                key = key,
                nonce = BouncyCastlePairingOracle.adbNonce(0),
                input = encrypted,
            ),
        )

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
        BouncyCastlePairingOracle.hkdfSha256(
            inputKeyMaterial = keyMaterial,
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
            outputSize = 16,
        )
}
