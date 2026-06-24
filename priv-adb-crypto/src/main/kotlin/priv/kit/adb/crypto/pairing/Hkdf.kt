package priv.kit.adb.crypto.pairing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HMAC_SHA256_ALGORITHM = "HmacSHA256"
private const val SHA256_OUTPUT_SIZE_BYTES = 32

internal object Hkdf {
    fun sha256(
        inputKeyMaterial: ByteArray,
        info: ByteArray,
        outputSize: Int,
    ): ByteArray {
        val prk = hmacSha256(ByteArray(SHA256_OUTPUT_SIZE_BYTES), inputKeyMaterial)
        var previous = ByteArray(0)
        var counter = 1
        val output = ByteArray(outputSize)
        var offset = 0
        while (offset < outputSize) {
            val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
            mac.init(SecretKeySpec(prk, HMAC_SHA256_ALGORITHM))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val copySize = minOf(previous.size, outputSize - offset)
            previous.copyInto(output, offset, 0, copySize)
            offset += copySize
            counter++
        }
        return output
    }

    private fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_SHA256_ALGORITHM))
        return mac.doFinal(input)
    }
}
