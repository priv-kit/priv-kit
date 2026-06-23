package priv.kit.bc.operator

import priv.kit.bc.asn1.x509.AlgorithmIdentifier
import java.io.OutputStream

public interface ContentSigner {
    public fun getAlgorithmIdentifier(): AlgorithmIdentifier
    public fun getOutputStream(): OutputStream
    public fun getSignature(): ByteArray
}
