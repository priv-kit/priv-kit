package priv.kit.adb.crypto.pairing

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter

internal object BouncyCastlePairingOracle {
    fun hkdfSha256(
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

    fun aesGcm(
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

    fun adbNonce(sequence: Long): ByteArray =
        ByteArray(12).also { nonce ->
            for (index in 0 until Long.SIZE_BYTES) {
                nonce[index] = (sequence ushr (index * 8)).toByte()
            }
        }
}
