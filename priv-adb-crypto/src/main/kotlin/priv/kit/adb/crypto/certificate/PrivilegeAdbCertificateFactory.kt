package priv.kit.adb.crypto.certificate

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone

private const val X509_CERTIFICATE_TYPE = "X.509"
private const val SHA256_WITH_RSA_SIGNATURE_ALGORITHM = "SHA256withRSA"
private const val SHA256_WITH_RSA_ENCRYPTION_OID = "1.2.840.113549.1.1.11"
private const val COMMON_NAME_OID = "2.5.4.3"
private const val ADB_CERTIFICATE_COMMON_NAME = "00"
private const val ADB_CERTIFICATE_NOT_BEFORE_MILLIS = 0L
private const val ADB_CERTIFICATE_NOT_AFTER_MILLIS = 2_461_449_600L * 1000L
private val ADB_CERTIFICATE_SERIAL_NUMBER = BigInteger.ONE

public object PrivilegeAdbCertificateFactory {
    public fun createRsaCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): X509Certificate {
        val encodedCertificate = encodeAdbCertificate(privateKey, publicKey)
        return CertificateFactory.getInstance(X509_CERTIFICATE_TYPE)
            .generateCertificate(ByteArrayInputStream(encodedCertificate)) as X509Certificate
    }

    private fun encodeAdbCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): ByteArray {
        val signatureAlgorithm = DerEncoding.sequence(
            DerEncoding.oid(SHA256_WITH_RSA_ENCRYPTION_OID),
            DerEncoding.nullValue(),
        )
        val name = DerEncoding.sequence(
            DerEncoding.set(
                DerEncoding.sequence(
                    DerEncoding.oid(COMMON_NAME_OID),
                    DerEncoding.utf8String(ADB_CERTIFICATE_COMMON_NAME),
                ),
            ),
        )
        val tbsCertificate = DerEncoding.sequence(
            DerEncoding.explicit(0, DerEncoding.integer(BigInteger.valueOf(2))),
            DerEncoding.integer(ADB_CERTIFICATE_SERIAL_NUMBER),
            signatureAlgorithm,
            name,
            DerEncoding.sequence(
                encodeTime(Date(ADB_CERTIFICATE_NOT_BEFORE_MILLIS)),
                encodeTime(Date(ADB_CERTIFICATE_NOT_AFTER_MILLIS)),
            ),
            name,
            publicKey.encoded,
        )
        val signature = Signature.getInstance(SHA256_WITH_RSA_SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(tbsCertificate)
            sign()
        }
        return DerEncoding.sequence(
            tbsCertificate,
            signatureAlgorithm,
            DerEncoding.bitString(signature),
        )
    }

    private fun encodeTime(date: Date): ByteArray {
        val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT)
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
