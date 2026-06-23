package priv.kit.bc.asn1.x509

import priv.kit.bc.internal.DerEncoding

public class AlgorithmIdentifier internal constructor(
    private val oid: String,
    private val parameters: ByteArray? = null,
) {
    internal val encoded: ByteArray
        get() = parameters?.let { DerEncoding.sequence(DerEncoding.oid(oid), it) }
            ?: DerEncoding.sequence(DerEncoding.oid(oid))

    internal companion object {
        internal fun sha256WithRsa(): AlgorithmIdentifier =
            AlgorithmIdentifier("1.2.840.113549.1.1.11", DerEncoding.nullValue())
    }
}
