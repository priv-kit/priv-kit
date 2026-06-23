package priv.kit.bc.asn1.x509

public class SubjectPublicKeyInfo private constructor(encoded: ByteArray) {
    internal val encoded: ByteArray = encoded.copyOf()

    public companion object {
        @JvmStatic
        public fun getInstance(encoded: ByteArray): SubjectPublicKeyInfo =
            SubjectPublicKeyInfo(encoded)
    }
}
