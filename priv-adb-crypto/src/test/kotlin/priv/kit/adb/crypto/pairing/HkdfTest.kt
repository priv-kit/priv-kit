package priv.kit.adb.crypto.pairing

import org.junit.Assert.assertArrayEquals
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.junit.Test

class HkdfTest {
    @Test
    fun matchesRfc5869Sha256NoSaltNoInfoVector() {
        val output = Hkdf.sha256(
            inputKeyMaterial = ByteArray(22) { 0x0b },
            info = ByteArray(0),
            outputSize = 42,
        )

        assertArrayEquals(
            Ed25519.hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            output,
        )
    }

    @Test
    fun matchesBouncyCastleForAdbKeyDerivation() {
        val keyMaterial = ByteArray(64) { (it * 13 + 7).toByte() }
        val info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII)

        assertArrayEquals(
            bouncyCastleHkdfSha256(keyMaterial, info, 16),
            Hkdf.sha256(keyMaterial, info, 16),
        )
    }

    private fun bouncyCastleHkdfSha256(
        inputKeyMaterial: ByteArray,
        info: ByteArray,
        outputSize: Int,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(inputKeyMaterial, ByteArray(0), info))
        val output = ByteArray(outputSize)
        generator.generateBytes(output, 0, output.size)
        return output
    }
}
