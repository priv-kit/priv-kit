package priv.kit.bc.operator.jcajce

import priv.kit.bc.asn1.x509.AlgorithmIdentifier
import priv.kit.bc.operator.ContentSigner
import java.io.OutputStream
import java.security.PrivateKey
import java.security.Signature

class JcaContentSignerBuilder(
    private val signatureAlgorithm: String,
) {
    fun build(privateKey: PrivateKey): ContentSigner {
        require(signatureAlgorithm.equals("SHA256withRSA", ignoreCase = true)) {
            "Only SHA256withRSA is supported"
        }

        val signature = Signature.getInstance("SHA256withRSA")
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
