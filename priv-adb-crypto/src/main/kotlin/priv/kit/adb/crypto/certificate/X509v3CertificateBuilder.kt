package priv.kit.adb.crypto.certificate

import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

internal class X509v3CertificateBuilder(
    private val issuer: X500Name,
    private val serial: BigInteger,
    private val notBefore: Date,
    private val notAfter: Date,
    private val dateLocale: Locale,
    private val subject: X500Name,
    private val publicKeyInfo: SubjectPublicKeyInfo,
) {
    internal fun build(signer: ContentSigner): X509CertificateHolder {
        val signatureAlgorithm = signer.getAlgorithmIdentifier()
        val tbsCertificate = buildTbsCertificate(signatureAlgorithm)
        signer.getOutputStream().use { stream ->
            stream.write(tbsCertificate)
        }
        return X509CertificateHolder(
            DerEncoding.sequence(
                tbsCertificate,
                signatureAlgorithm.encoded,
                DerEncoding.bitString(signer.getSignature()),
            ),
        )
    }

    private fun buildTbsCertificate(signatureAlgorithm: AlgorithmIdentifier): ByteArray =
        DerEncoding.sequence(
            DerEncoding.explicit(0, DerEncoding.integer(BigInteger.valueOf(2))),
            DerEncoding.integer(serial),
            signatureAlgorithm.encoded,
            issuer.encoded,
            DerEncoding.sequence(encodeTime(notBefore), encodeTime(notAfter)),
            subject.encoded,
            publicKeyInfo.encoded,
        )

    private fun encodeTime(date: Date): ByteArray {
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", dateLocale)
        formatter.timeZone = SimpleTimeZone(0, "Z")

        val value = formatter.format(date) + "Z"
        val year = value.substring(0, 4).toInt()
        return if (year in 1950..2049) {
            DerEncoding.utcTime(value.substring(2))
        } else {
            DerEncoding.generalizedTime(value)
        }
    }
}
