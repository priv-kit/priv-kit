package priv.kit.bc.cert

import priv.kit.bc.asn1.x500.X500Name
import priv.kit.bc.asn1.x509.AlgorithmIdentifier
import priv.kit.bc.asn1.x509.SubjectPublicKeyInfo
import priv.kit.bc.internal.DerEncoding
import priv.kit.bc.operator.ContentSigner
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

class X509v3CertificateBuilder(
    private val issuer: X500Name,
    private val serial: BigInteger,
    private val notBefore: Date,
    private val notAfter: Date,
    private val dateLocale: Locale,
    private val subject: X500Name,
    private val publicKeyInfo: SubjectPublicKeyInfo,
) {
    constructor(
        issuer: X500Name,
        serial: BigInteger,
        notBefore: Date,
        notAfter: Date,
        subject: X500Name,
        publicKeyInfo: SubjectPublicKeyInfo,
    ) : this(issuer, serial, notBefore, notAfter, Locale.ROOT, subject, publicKeyInfo)

    fun build(signer: ContentSigner): X509CertificateHolder {
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
