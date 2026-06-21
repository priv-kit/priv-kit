package priv.kit.ssl.adb

import priv.kit.ssl.internal.Aes128Gcm
import priv.kit.ssl.internal.Spake25519Context
import priv.kit.ssl.internal.Spake25519Role
import java.security.SecureRandom

class PrivilegeSslAdbPairingContext private constructor(
    private var spake2: Spake25519Context?,
) {
    val msg: ByteArray = checkNotNull(spake2).msg.copyOf()
    private var cipher: Aes128Gcm? = null

    fun initCipher(theirMsg: ByteArray): Boolean {
        val context = spake2 ?: return false
        val keyMaterial = context.processMsg(theirMsg) ?: return false
        cipher = Aes128Gcm(keyMaterial)
        spake2 = null
        return true
    }

    fun encrypt(input: ByteArray): ByteArray? = cipher?.encrypt(input)

    fun decrypt(input: ByteArray): ByteArray? = cipher?.decrypt(input)

    fun destroy() {
        spake2 = null
        cipher = null
    }

    companion object {
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        fun createClient(password: ByteArray): PrivilegeSslAdbPairingContext? =
            create(Spake25519Role.ALICE, CLIENT_NAME, SERVER_NAME, password, SecureRandom())

        fun createServer(password: ByteArray): PrivilegeSslAdbPairingContext? =
            create(Spake25519Role.BOB, SERVER_NAME, CLIENT_NAME, password, SecureRandom())

        internal fun createClient(
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeSslAdbPairingContext? =
            create(Spake25519Role.ALICE, CLIENT_NAME, SERVER_NAME, password, random)

        internal fun createServer(
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeSslAdbPairingContext? =
            create(Spake25519Role.BOB, SERVER_NAME, CLIENT_NAME, password, random)

        private fun create(
            role: Spake25519Role,
            myName: ByteArray,
            theirName: ByteArray,
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeSslAdbPairingContext? {
            val context = Spake25519Context.create(role, myName, theirName, password, random)
            return context?.let(::PrivilegeSslAdbPairingContext)
        }
    }
}
