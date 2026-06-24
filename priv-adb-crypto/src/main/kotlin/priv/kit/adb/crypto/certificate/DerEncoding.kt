package priv.kit.adb.crypto.certificate

import java.math.BigInteger

internal object DerEncoding {
    fun sequence(vararg values: ByteArray): ByteArray = tagged(0x30, values.concat())

    fun set(vararg values: ByteArray): ByteArray = tagged(0x31, values.concat())

    fun explicit(tag: Int, value: ByteArray): ByteArray = tagged(0xa0 + tag, value)

    fun integer(value: BigInteger): ByteArray = tagged(0x02, value.toByteArray())

    fun oid(value: String): ByteArray {
        val parts = value.split('.').map(String::toLong)
        require(parts.size >= 2) { "OID must contain at least two components" }
        require(parts[0] in 0..2 && parts[1] in 0..39) { "Invalid OID: $value" }

        val body = mutableListOf<Byte>()
        body += (parts[0] * 40 + parts[1]).toByte()
        parts.drop(2).forEach { part ->
            require(part >= 0) { "Invalid OID component: $part" }
            val encoded = mutableListOf<Byte>()
            var current = part
            encoded += (current and 0x7f).toByte()
            current = current ushr 7
            while (current > 0) {
                encoded += ((current and 0x7f) or 0x80).toByte()
                current = current ushr 7
            }
            body += encoded.asReversed()
        }
        return tagged(0x06, body.toByteArray())
    }

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun utf8String(value: String): ByteArray = tagged(0x0c, value.toByteArray(Charsets.UTF_8))

    fun utcTime(value: String): ByteArray = tagged(0x17, value.toByteArray(Charsets.US_ASCII))

    fun generalizedTime(value: String): ByteArray = tagged(0x18, value.toByteArray(Charsets.US_ASCII))

    fun bitString(value: ByteArray): ByteArray = tagged(0x03, byteArrayOf(0x00) + value)

    private fun tagged(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray {
        require(size >= 0) { "Negative DER length" }
        if (size < 0x80) return byteArrayOf(size.toByte())

        var current = size
        val bytes = mutableListOf<Byte>()
        while (current > 0) {
            bytes += (current and 0xff).toByte()
            current = current ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.asReversed().toByteArray()
    }

    private fun Array<out ByteArray>.concat(): ByteArray {
        val result = ByteArray(sumOf(ByteArray::size))
        var offset = 0
        forEach { value ->
            value.copyInto(result, offset)
            offset += value.size
        }
        return result
    }
}
