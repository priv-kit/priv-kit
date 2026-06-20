package priv.kit.bc.asn1.x509

class SubjectPublicKeyInfo private constructor(encoded: ByteArray) {
    internal val encoded: ByteArray = encoded.copyOf()

    companion object {
        @JvmStatic
        fun getInstance(encoded: ByteArray): SubjectPublicKeyInfo =
            SubjectPublicKeyInfo(encoded)
    }
}
