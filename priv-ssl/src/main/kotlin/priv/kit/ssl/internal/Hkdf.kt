package priv.kit.ssl.internal

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object Hkdf {
    fun sha256(
        inputKeyMaterial: ByteArray,
        info: ByteArray,
        outputSize: Int,
    ): ByteArray {
        val prk = hmacSha256(ByteArray(32), inputKeyMaterial)
        var previous = ByteArray(0)
        var counter = 1
        val output = ByteArray(outputSize)
        var offset = 0
        while (offset < outputSize) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
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
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
}
