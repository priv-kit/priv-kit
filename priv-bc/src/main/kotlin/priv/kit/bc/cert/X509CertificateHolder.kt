package priv.kit.bc.cert

public class X509CertificateHolder internal constructor(encoded: ByteArray) {
    private val derEncoded: ByteArray = encoded.copyOf()

    public val encoded: ByteArray
        get() = derEncoded.copyOf()
}
