package priv.kit.adb.crypto.certificate

private const val SHA256_WITH_RSA_ENCRYPTION_OID = "1.2.840.113549.1.1.11"

internal class AlgorithmIdentifier(
    private val oid: String,
    private val parameters: ByteArray? = null,
) {
    internal val encoded: ByteArray
        get() = parameters?.let { DerEncoding.sequence(DerEncoding.oid(oid), it) }
            ?: DerEncoding.sequence(DerEncoding.oid(oid))

    internal companion object {
        internal fun sha256WithRsa(): AlgorithmIdentifier =
            AlgorithmIdentifier(SHA256_WITH_RSA_ENCRYPTION_OID, DerEncoding.nullValue())
    }
}
