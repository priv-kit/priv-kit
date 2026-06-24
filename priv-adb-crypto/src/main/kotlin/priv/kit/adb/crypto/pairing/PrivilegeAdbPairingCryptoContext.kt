package priv.kit.adb.crypto.pairing

import java.security.SecureRandom

public class PrivilegeAdbPairingCryptoContext private constructor(
    private var spake2: Spake25519Context?,
) {
    public val msg: ByteArray = checkNotNull(spake2).msg.copyOf()
    private var cipher: Aes128Gcm? = null

    public fun initCipher(theirMsg: ByteArray): Boolean {
        val context = spake2 ?: return false
        val keyMaterial = context.processMsg(theirMsg) ?: return false
        cipher = Aes128Gcm(keyMaterial)
        spake2 = null
        return true
    }

    public fun encrypt(input: ByteArray): ByteArray? = cipher?.encrypt(input)

    public fun decrypt(input: ByteArray): ByteArray? = cipher?.decrypt(input)

    public fun destroy() {
        spake2 = null
        cipher = null
    }

    public companion object {
        // These transcript labels are ADB pairing protocol bytes; the trailing NUL is intentional.
        private const val CLIENT_TRANSCRIPT_LABEL = "adb pair client\u0000"
        private const val SERVER_TRANSCRIPT_LABEL = "adb pair server\u0000"
        private val CLIENT_NAME = CLIENT_TRANSCRIPT_LABEL.toByteArray(Charsets.US_ASCII)
        private val SERVER_NAME = SERVER_TRANSCRIPT_LABEL.toByteArray(Charsets.US_ASCII)

        public fun createClient(password: ByteArray): PrivilegeAdbPairingCryptoContext =
            create(Spake25519Role.ALICE, CLIENT_NAME, SERVER_NAME, password, SecureRandom())

        internal fun createClient(
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeAdbPairingCryptoContext =
            create(Spake25519Role.ALICE, CLIENT_NAME, SERVER_NAME, password, random)

        internal fun createServer(
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeAdbPairingCryptoContext =
            create(Spake25519Role.BOB, SERVER_NAME, CLIENT_NAME, password, random)

        private fun create(
            role: Spake25519Role,
            myName: ByteArray,
            theirName: ByteArray,
            password: ByteArray,
            random: SecureRandom,
        ): PrivilegeAdbPairingCryptoContext {
            val context = Spake25519Context.create(role, myName, theirName, password, random)
            return PrivilegeAdbPairingCryptoContext(context)
        }
    }
}
