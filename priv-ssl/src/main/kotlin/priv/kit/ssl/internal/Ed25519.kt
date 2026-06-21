package priv.kit.ssl.internal

import java.math.BigInteger

internal data class Ed25519Point(
    val x: BigInteger,
    val y: BigInteger,
)

internal object Ed25519 {
    private val TWO = BigInteger.valueOf(2)

    val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))
    val ORDER: BigInteger = TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))

    private val D: BigInteger = BigInteger.valueOf(-121665).mod(P)
        .multiply(BigInteger.valueOf(121666).modInverse(P))
        .mod(P)
    private val SQRT_M1: BigInteger = TWO.modPow(P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)), P)
    private val SQRT_EXPONENT: BigInteger = P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8))

    val IDENTITY = Ed25519Point(BigInteger.ZERO, BigInteger.ONE)
    val BASE_POINT: Ed25519Point = checkNotNull(decode(hex("5866666666666666666666666666666666666666666666666666666666666666")))

    fun decode(encoded: ByteArray): Ed25519Point? {
        if (encoded.size != 32) return null
        val yBytes = encoded.copyOf()
        val sign = (yBytes[31].toInt() ushr 7) and 1
        yBytes[31] = (yBytes[31].toInt() and 0x7f).toByte()
        val y = littleEndianToBigInteger(yBytes)
        if (y >= P) return null

        val y2 = y.multiply(y).mod(P)
        val numerator = y2.subtract(BigInteger.ONE).mod(P)
        val denominator = D.multiply(y2).add(BigInteger.ONE).mod(P)
        val x2 = try {
            numerator.multiply(denominator.modInverse(P)).mod(P)
        } catch (_: ArithmeticException) {
            return null
        }

        var x = sqrt(x2) ?: return null
        if (x == BigInteger.ZERO && sign == 1) return null
        if (x.testBit(0) != (sign == 1)) {
            x = P.subtract(x).mod(P)
        }

        return Ed25519Point(x, y)
    }

    fun encode(point: Ed25519Point): ByteArray {
        val out = bigIntegerToLittleEndian(point.y.mod(P), 32)
        if (point.x.mod(P).testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    fun add(a: Ed25519Point, b: Ed25519Point): Ed25519Point {
        val x1 = a.x.mod(P)
        val y1 = a.y.mod(P)
        val x2 = b.x.mod(P)
        val y2 = b.y.mod(P)
        val x1x2 = x1.multiply(x2).mod(P)
        val y1y2 = y1.multiply(y2).mod(P)
        val dxxyy = D.multiply(x1x2).multiply(y1y2).mod(P)

        val xNumerator = x1.multiply(y2).add(y1.multiply(x2)).mod(P)
        val xDenominator = BigInteger.ONE.add(dxxyy).mod(P)
        val yNumerator = y1y2.add(x1x2).mod(P)
        val yDenominator = BigInteger.ONE.subtract(dxxyy).mod(P)

        val x3 = xNumerator.multiply(xDenominator.modInverse(P)).mod(P)
        val y3 = yNumerator.multiply(yDenominator.modInverse(P)).mod(P)
        return Ed25519Point(x3, y3)
    }

    fun subtract(a: Ed25519Point, b: Ed25519Point): Ed25519Point =
        add(a, Ed25519Point(P.subtract(b.x).mod(P), b.y))

    fun scalarMultiply(point: Ed25519Point, scalar: BigInteger): Ed25519Point {
        var k = scalar
        var result = IDENTITY
        var addend = point
        while (k.signum() > 0) {
            if (k.testBit(0)) {
                result = add(result, addend)
            }
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    fun reduceScalar(input: ByteArray): BigInteger =
        littleEndianToBigInteger(input).mod(ORDER)

    fun littleEndianToBigInteger(input: ByteArray): BigInteger =
        BigInteger(1, input.reversedArray())

    fun bigIntegerToLittleEndian(input: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var value = input
        for (index in 0 until size) {
            out[index] = value.and(BigInteger.valueOf(0xff)).toByte()
            value = value.shiftRight(8)
        }
        return out
    }

    internal fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun sqrt(value: BigInteger): BigInteger? {
        if (value == BigInteger.ZERO) return BigInteger.ZERO
        var candidate = value.modPow(SQRT_EXPONENT, P)
        if (candidate.multiply(candidate).subtract(value).mod(P) != BigInteger.ZERO) {
            candidate = candidate.multiply(SQRT_M1).mod(P)
        }
        return if (candidate.multiply(candidate).subtract(value).mod(P) == BigInteger.ZERO) {
            candidate
        } else {
            null
        }
    }
}
