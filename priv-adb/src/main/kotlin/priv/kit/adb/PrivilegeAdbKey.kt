package priv.kit.adb

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import priv.kit.bc.asn1.x500.X500Name
import priv.kit.bc.asn1.x509.SubjectPublicKeyInfo
import priv.kit.bc.cert.X509v3CertificateBuilder
import priv.kit.bc.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

internal interface PrivilegeAdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

internal class PrivilegeAdbSharedPreferencesKeyStore(
    private val preferences: SharedPreferences,
    private val preferenceKey: String = PREFERENCE_KEY,
) : PrivilegeAdbKeyStore {
    override fun put(bytes: ByteArray) {
        preferences.edit()
            .putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP)))
            .apply()
    }

    override fun get(): ByteArray? {
        if (!preferences.contains(preferenceKey)) return null
        return Base64.decode(preferences.getString(preferenceKey, null), Base64.NO_WRAP)
    }

    companion object {
        private const val PREFERENCE_KEY = "adbkey"

        fun create(
            context: Context,
            signature: String = PrivilegeAdbIdentity.DEFAULT_STORAGE_SIGNATURE,
        ): PrivilegeAdbSharedPreferencesKeyStore =
            PrivilegeAdbSharedPreferencesKeyStore(
                context.applicationContext.getSharedPreferences("privilege_adb", Context.MODE_PRIVATE),
                preferenceKey = preferenceKeyForSignature(signature),
            )

        private fun preferenceKeyForSignature(signature: String): String {
            val normalizedSignature = signature.trim()
            if (normalizedSignature == PrivilegeAdbIdentity.DEFAULT_STORAGE_SIGNATURE) return PREFERENCE_KEY
            return PREFERENCE_KEY + "_" + normalizedSignature.toByteArray().sha256Hex().take(32)
        }
    }
}

internal class PrivilegeAdbKey(
    private val keyStore: PrivilegeAdbKeyStore,
    private val name: String,
) {
    private val encryptionKey: Key = getOrCreateEncryptionKey()
        ?: throw PrivilegeAdbException("Failed to generate ADB encryption key")
    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey =
        KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4),
        ) as RSAPublicKey
    private val certificate: X509Certificate = createCertificate()
    private val adbPublicKeyPayload: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        publicKey.adbEncodedPayload()
    }

    val adbPublicKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        adbPublicKeyPayload.adbPublicKeyWithName(name)
    }

    val adbPublicKeyFingerprint: String by lazy(LazyThreadSafetyMode.NONE) {
        adbPublicKeyPayload.sha256Fingerprint()
    }

    val sslContext: SSLContext by lazy(LazyThreadSafetyMode.NONE) {
        SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        }
    }

    fun sign(data: ByteArray?): ByteArray {
        val token = requireNotNull(data) { "ADB auth token is null" }
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(token)
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)?.let { return it }

        val parameterSpec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(parameterSpec)
        return generator.generateKey()
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        keyStore.get()?.let { ciphertext ->
            runCatching {
                val plaintext = decrypt(ciphertext, aad)
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            }.getOrNull()?.let { return it }
        }

        val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
        keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        val generatedPrivateKey = keyPairGenerator.generateKeyPair().private as RSAPrivateKey
        encrypt(generatedPrivateKey.encoded, aad)?.let(keyStore::put)
        return generatedPrivateKey
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) return null
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) return null
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun createCertificate(): X509Certificate {
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val certificateHolder = X509v3CertificateBuilder(
            X500Name("CN=00"),
            BigInteger.ONE,
            Date(0),
            Date(2_461_449_600L * 1000L),
            Locale.ROOT,
            X500Name("CN=00"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded),
        ).build(signer)
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateHolder.encoded)) as X509Certificate
    }

    private val keyManager: X509ExtendedKeyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(
                keyTypes: Array<out String>,
                issuers: Array<out Principal>?,
                socket: Socket?,
            ): String? = keyTypes.firstOrNull { it == "RSA" }?.let { alias }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(certificate) else null

            override fun getPrivateKey(alias: String?): PrivateKey? =
                if (alias == this.alias) privateKey else null

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        }

    private val trustManager: X509ExtendedTrustManager
        get() = PrivilegeAdbTrustManager

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_privilege_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
            0x04, 0x14,
        )
        private val PADDING = byteArrayOf(0x00, 0x01) +
            ByteArray(ANDROID_PUBKEY_MODULUS_SIZE - 3 - SHA1_DIGEST_INFO_PREFIX.size - SHA1_SIZE_BYTES) { (-1).toByte() } +
            byteArrayOf(0x00) +
            SHA1_DIGEST_INFO_PREFIX

        private const val SHA1_SIZE_BYTES = 20
    }
}

// Wireless ADB does not use the platform CA store for daemon trust. Pairing and ADB auth bind this client key
// to the daemon, so this manager is scoped to PrivilegeAdbKey TLS sockets instead of general app networking.
@SuppressLint("CustomX509TrustManager")
private object PrivilegeAdbTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
internal const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
internal const val RSA_PUBLIC_KEY_SIZE = 524

internal fun BigInteger.toAdbEncoded(): IntArray {
    val encoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)
    var tmp = this.add(BigInteger.ZERO)
    for (index in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        encoded[index] = out[1].toInt()
    }
    return encoded
}

internal fun RSAPublicKey.adbEncoded(name: String): ByteArray =
    adbEncodedPayload().adbPublicKeyWithName(name)

private fun RSAPublicKey.adbEncodedPayload(): ByteArray {
    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSA_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())
    return buffer.array()
}

private fun ByteArray.adbPublicKeyWithName(name: String): ByteArray {
    val base64Bytes = Base64.encode(this, Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    return ByteArray(base64Bytes.size + nameBytes.size).also { bytes ->
        base64Bytes.copyInto(bytes)
        nameBytes.copyInto(bytes, base64Bytes.size)
    }
}

private fun ByteArray.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(":") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
