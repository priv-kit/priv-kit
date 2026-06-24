package priv.kit.adb.crypto.certificate

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

private const val ADB_CERTIFICATE_NAME = "CN=00"
private const val X509_CERTIFICATE_TYPE = "X.509"
private const val ADB_CERTIFICATE_NOT_BEFORE_MILLIS = 0L
private const val ADB_CERTIFICATE_NOT_AFTER_MILLIS = 2_461_449_600L * 1000L
private val ADB_CERTIFICATE_SERIAL_NUMBER = BigInteger.ONE

public object PrivilegeAdbCertificateFactory {
    public fun createRsaCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
    ): X509Certificate {
        val signer = JcaContentSignerBuilder(SHA256_WITH_RSA_SIGNATURE_ALGORITHM).build(privateKey)
        val certificateHolder = X509v3CertificateBuilder(
            X500Name(ADB_CERTIFICATE_NAME),
            ADB_CERTIFICATE_SERIAL_NUMBER,
            Date(ADB_CERTIFICATE_NOT_BEFORE_MILLIS),
            Date(ADB_CERTIFICATE_NOT_AFTER_MILLIS),
            Locale.ROOT,
            X500Name(ADB_CERTIFICATE_NAME),
            SubjectPublicKeyInfo(publicKey.encoded),
        ).build(signer)
        return CertificateFactory.getInstance(X509_CERTIFICATE_TYPE)
            .generateCertificate(ByteArrayInputStream(certificateHolder.encoded)) as X509Certificate
    }
}
