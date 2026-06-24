package priv.kit.adb.crypto.certificate

internal class X509CertificateHolder(encoded: ByteArray) {
    private val derEncoded: ByteArray = encoded.copyOf()

    internal val encoded: ByteArray
        get() = derEncoded.copyOf()
}
