package priv.kit.ssl.internal

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

internal enum class Spake25519Role {
    ALICE,
    BOB,
}

internal class Spake25519Context private constructor(
    private val role: Spake25519Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val privateKey: BigInteger,
    private val passwordScalar: BigInteger,
    private val passwordHash: ByteArray,
    val msg: ByteArray,
) {
    private var processed = false

    fun processMsg(theirMsg: ByteArray): ByteArray? {
        if (processed || theirMsg.size != MESSAGE_SIZE) return null
        val qStar = Ed25519.decode(theirMsg) ?: return null
        val peerMaskPoint = Ed25519.scalarMultiply(peerMaskBase(), passwordScalar)
        val q = Ed25519.subtract(qStar, peerMaskPoint)
        val shared = Ed25519.scalarMultiply(q, privateKey)
        val sharedEncoded = Ed25519.encode(shared)
        val key = transcriptHash(sharedEncoded, theirMsg)
        processed = true
        return key
    }

    private fun transcriptHash(sharedEncoded: ByteArray, theirMsg: ByteArray): ByteArray {
        val sha = MessageDigest.getInstance("SHA-512")
        if (role == Spake25519Role.ALICE) {
            sha.updateWithLengthPrefix(myName)
            sha.updateWithLengthPrefix(theirName)
            sha.updateWithLengthPrefix(msg)
            sha.updateWithLengthPrefix(theirMsg)
        } else {
            sha.updateWithLengthPrefix(theirName)
            sha.updateWithLengthPrefix(myName)
            sha.updateWithLengthPrefix(theirMsg)
            sha.updateWithLengthPrefix(msg)
        }
        sha.updateWithLengthPrefix(sharedEncoded)
        sha.updateWithLengthPrefix(passwordHash)
        return sha.digest()
    }

    private fun peerMaskBase(): Ed25519Point =
        if (role == Spake25519Role.ALICE) N_POINT else M_POINT

    companion object {
        private const val MESSAGE_SIZE = 32
        private val M_POINT = checkNotNull(Ed25519.decode(Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e")))
        private val N_POINT = checkNotNull(Ed25519.decode(Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778")))

        fun create(
            role: Spake25519Role,
            myName: ByteArray,
            theirName: ByteArray,
            password: ByteArray,
            random: SecureRandom,
        ): Spake25519Context {
            val privateKey = privateScalar(random)
            val p = Ed25519.scalarMultiply(Ed25519.BASE_POINT, privateKey)
            val passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
            val passwordScalar = passwordScalar(passwordHash)
            val maskPoint = Ed25519.scalarMultiply(
                if (role == Spake25519Role.ALICE) M_POINT else N_POINT,
                passwordScalar,
            )
            val pStar = Ed25519.add(p, maskPoint)
            val msg = Ed25519.encode(pStar)
            return Spake25519Context(
                role = role,
                myName = myName.copyOf(),
                theirName = theirName.copyOf(),
                privateKey = privateKey,
                passwordScalar = passwordScalar,
                passwordHash = passwordHash,
                msg = msg,
            )
        }

        private fun privateScalar(random: SecureRandom): BigInteger {
            val bytes = ByteArray(64)
            random.nextBytes(bytes)
            return Ed25519.reduceScalar(bytes).shiftLeft(3)
        }

        private fun passwordScalar(passwordHash: ByteArray): BigInteger {
            var scalar = Ed25519.reduceScalar(passwordHash)
            for (bit in 0..2) {
                if (scalar.testBit(bit)) {
                    scalar = scalar.add(Ed25519.ORDER.shiftLeft(bit))
                }
            }
            check(!scalar.testBit(0) && !scalar.testBit(1) && !scalar.testBit(2))
            return scalar
        }

        private fun MessageDigest.updateWithLengthPrefix(data: ByteArray) {
            var size = data.size.toLong()
            val length = ByteArray(8)
            for (index in length.indices) {
                length[index] = (size and 0xff).toByte()
                size = size ushr 8
            }
            update(length)
            update(data)
        }
    }
}
