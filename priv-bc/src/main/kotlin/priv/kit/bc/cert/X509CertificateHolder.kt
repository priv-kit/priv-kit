package priv.kit.bc.cert

class X509CertificateHolder internal constructor(encoded: ByteArray) {
    private val derEncoded: ByteArray = encoded.copyOf()

    val encoded: ByteArray
        get() = derEncoded.copyOf()
}
