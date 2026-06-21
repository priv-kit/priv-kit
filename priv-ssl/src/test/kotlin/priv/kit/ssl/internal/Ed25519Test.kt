package priv.kit.ssl.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ed25519Test {
    @Test
    fun decodesAndEncodesBasePoint() {
        val encoded = Ed25519.hex("5866666666666666666666666666666666666666666666666666666666666666")
        val point = checkNotNull(Ed25519.decode(encoded))

        assertArrayEquals(encoded, Ed25519.encode(point))
    }

    @Test
    fun basePointHasExpectedOrder() {
        assertEquals(Ed25519.IDENTITY, Ed25519.scalarMultiply(Ed25519.BASE_POINT, Ed25519.ORDER))
    }

    @Test
    fun rejectsNonCanonicalY() {
        val encoded = Ed25519.bigIntegerToLittleEndian(Ed25519.P, 32)

        assertNull(Ed25519.decode(encoded))
    }
}
