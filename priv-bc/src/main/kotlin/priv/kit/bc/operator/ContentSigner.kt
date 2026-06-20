package priv.kit.bc.operator

import priv.kit.bc.asn1.x509.AlgorithmIdentifier
import java.io.OutputStream

interface ContentSigner {
    fun getAlgorithmIdentifier(): AlgorithmIdentifier
    fun getOutputStream(): OutputStream
    fun getSignature(): ByteArray
}
