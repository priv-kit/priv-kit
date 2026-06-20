package priv.kit.bc.cert

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import priv.kit.bc.asn1.x500.X500Name
import priv.kit.bc.asn1.x509.SubjectPublicKeyInfo
import priv.kit.bc.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

class X509v3CertificateBuilderTest {
    @Test
    fun matchesBouncyCastleForAdbCertificateShape() {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val notBefore = Date(0)
        val notAfter = Date(2_461_449_600L * 1000L)

        val actual = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            notBefore,
            notAfter,
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)).encoded

        val expected = org.bouncycastle.cert.X509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=00"),
            BigInteger.ONE,
            notBefore,
            notAfter,
            Locale.ROOT,
            org.bouncycastle.asn1.x500.X500Name("CN=00"),
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
            .encoded

        assertArrayEquals(expected, actual)

        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(actual)) as X509Certificate
        certificate.verify(keyPair.public)
        assertEquals(BigInteger.ONE, certificate.serialNumber)
        assertEquals("CN=00", certificate.subjectX500Principal.name)
    }
}
