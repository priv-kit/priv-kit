package priv.kit.adb.crypto.certificate

internal class X500Name(dirName: String) {
    internal val encoded: ByteArray = encode(dirName)

    private companion object {
        private const val COMMON_NAME_PREFIX = "CN"
        private const val COMMON_NAME_OID = "2.5.4.3"

        private fun encode(dirName: String): ByteArray {
            val parts = dirName.split("=", limit = 2)
            require(parts.size == 2 && parts[0].equals(COMMON_NAME_PREFIX, ignoreCase = true)) {
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
