package priv.kit.adb.crypto.certificate

import java.io.OutputStream
import java.security.PrivateKey
import java.security.Signature

internal const val SHA256_WITH_RSA_SIGNATURE_ALGORITHM = "SHA256withRSA"

internal class JcaContentSignerBuilder(
    private val signatureAlgorithm: String,
) {
    internal fun build(privateKey: PrivateKey): ContentSigner {
        require(signatureAlgorithm.equals(SHA256_WITH_RSA_SIGNATURE_ALGORITHM, ignoreCase = true)) {
            "Only $SHA256_WITH_RSA_SIGNATURE_ALGORITHM is supported"
        }

        val signature = Signature.getInstance(SHA256_WITH_RSA_SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)

        return object : ContentSigner {
            private val stream = object : OutputStream() {
                override fun write(b: Int) {
                    signature.update(b.toByte())
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    signature.update(b, off, len)
                }
            }

            override fun getAlgorithmIdentifier(): AlgorithmIdentifier =
                AlgorithmIdentifier.sha256WithRsa()

            override fun getOutputStream(): OutputStream = stream

            override fun getSignature(): ByteArray = signature.sign()
        }
    }
}
