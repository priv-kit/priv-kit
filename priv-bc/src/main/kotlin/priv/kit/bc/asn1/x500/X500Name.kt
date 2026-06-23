package priv.kit.bc.asn1.x500

import priv.kit.bc.internal.DerEncoding

public class X500Name public constructor(dirName: String) {
    internal val encoded: ByteArray = encode(dirName)

    private companion object {
        private const val COMMON_NAME_OID = "2.5.4.3"

        private fun encode(dirName: String): ByteArray {
            val parts = dirName.split("=", limit = 2)
            require(parts.size == 2 && parts[0].equals("CN", ignoreCase = true)) {
                "Only CN names are supported"
            }
            val value = parts[1]
            require(value.length <= 64) { "commonName length exceeds 64: $value" }

            return DerEncoding.sequence(
                DerEncoding.set(
                    DerEncoding.sequence(
                        DerEncoding.oid(COMMON_NAME_OID),
                        DerEncoding.utf8String(value),
                    ),
                ),
            )
        }
    }
}
