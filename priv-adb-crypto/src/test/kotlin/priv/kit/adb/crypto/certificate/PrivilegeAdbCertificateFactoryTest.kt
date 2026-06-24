package priv.kit.adb.crypto.certificate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import java.util.Locale

class PrivilegeAdbCertificateFactoryTest {
    @Test
    fun matchesBouncyCastleForAdbCertificateShape() {
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048)
        val keyPair = keyPairGenerator.generateKeyPair()
        val notBefore = Date(0)
        val notAfter = Date(2_461_449_600L * 1000L)

        val actualCertificate = PrivilegeAdbCertificateFactory.createRsaCertificate(keyPair.private, keyPair.public)
        val actual = actualCertificate.encoded

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

        actualCertificate.verify(keyPair.public)
        assertEquals(BigInteger.ONE, actualCertificate.serialNumber)
        assertEquals("CN=00", actualCertificate.subjectX500Principal.name)
    }
}
