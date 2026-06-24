package priv.kit.adb.crypto.pairing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PrivilegeAdbPairingCryptoContextTest {
    @Test
    fun exchangesMessagesWithMatchingPassword() {
        val password = byteArrayOf(0x4f, 0x5a, 0x01, 0x46)
        val client = PrivilegeAdbPairingCryptoContext.createClient(password, FixedSecureRandom(1))
        val server = PrivilegeAdbPairingCryptoContext.createServer(password, FixedSecureRandom(2))

        assertEquals(32, client.msg.size)
        assertEquals(32, server.msg.size)
        assertTrue(client.initCipher(server.msg))
        assertTrue(server.initCipher(client.msg))

        val message = byteArrayOf(0x2a, 0x2b, 0x2c, 0xff.toByte(), 0x45, 0x12, 0x33)
        val clientEncrypted = checkNotNull(client.encrypt(message))
        assertNotEquals(message.toList(), clientEncrypted.toList())
        assertArrayEquals(message, server.decrypt(clientEncrypted))

        val serverEncrypted = checkNotNull(server.encrypt(message))
        assertArrayEquals(message, client.decrypt(serverEncrypted))
    }

    @Test
    fun rejectsDifferentPasswordsAtDecryptTime() {
        val client =
            PrivilegeAdbPairingCryptoContext.createClient(byteArrayOf(0x01, 0x02, 0x03), FixedSecureRandom(3))
        val server =
            PrivilegeAdbPairingCryptoContext.createServer(byteArrayOf(0x01, 0x02, 0x04), FixedSecureRandom(4))

        assertTrue(client.initCipher(server.msg))
        assertTrue(server.initCipher(client.msg))

        val encrypted = checkNotNull(client.encrypt(byteArrayOf(0x2a, 0x2b, 0x2c)))
        assertNull(server.decrypt(encrypted))
    }

    @Test
    fun rejectsInvalidPeerMessage() {
        val client = PrivilegeAdbPairingCryptoContext.createClient(byteArrayOf(0x01), FixedSecureRandom(5))

        assertFalse(client.initCipher(ByteArray(31)))
        assertFalse(client.initCipher(ByteArray(33)))
    }

    @Test
    fun destroyClearsCipherState() {
        val password = byteArrayOf(0x4f, 0x5a, 0x01, 0x46)
        val client = PrivilegeAdbPairingCryptoContext.createClient(password, FixedSecureRandom(6))
        val server = PrivilegeAdbPairingCryptoContext.createServer(password, FixedSecureRandom(7))
        assertTrue(client.initCipher(server.msg))

        client.destroy()

        assertNull(client.encrypt(byteArrayOf(0x01)))
        assertNull(client.decrypt(byteArrayOf(0x01)))
    }

    private class FixedSecureRandom(seed: Int) : SecureRandom() {
        private var value = seed

        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                value = value * 1103515245 + 12345
                bytes[index] = (value ushr 16).toByte()
            }
        }
    }
}
