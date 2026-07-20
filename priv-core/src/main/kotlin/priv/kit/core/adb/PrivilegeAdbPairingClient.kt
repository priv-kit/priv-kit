package priv.kit.core.adb

import com.android.org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket
import priv.kit.adb.crypto.pairing.PrivilegeAdbPairingCryptoContext

private const val CURRENT_KEY_HEADER_VERSION = 1.toByte()
private const val MIN_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_PEER_INFO_SIZE = 8192
private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
// ADB pairing derives the SPAKE2 password from TLS exported keying material using this label.
private const val ADB_PAIRING_EXPORTED_KEY_LABEL = "adb-label\u0000"
private const val EXPORTED_KEY_SIZE = 64
private const val PAIRING_PACKET_HEADER_SIZE = 6

private class PeerInfo(
    private val type: Byte,
    data: ByteArray,
) {
    private val data = ByteArray(MAX_PEER_INFO_SIZE - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(MAX_PEER_INFO_SIZE - 1))
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.put(type)
        buffer.put(data)
    }

    companion object {
        const val ADB_RSA_PUB_KEY = 0.toByte()

        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(MAX_PEER_INFO_SIZE - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int,
) {
    fun writeTo(buffer: ByteBuffer) {
        buffer.put(version)
        buffer.put(type)
        buffer.putInt(payload)
    }

    companion object {
        const val TYPE_SPAKE2_MSG = 0.toByte()
        const val TYPE_PEER_INFO = 1.toByte()

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int
            if (version !in MIN_SUPPORTED_KEY_HEADER_VERSION..MAX_SUPPORTED_KEY_HEADER_VERSION) return null
            if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) return null
            if (payload !in 1..MAX_PAYLOAD_SIZE) return null
            return PairingPacketHeader(version, type, payload)
        }
    }
}

internal class PrivilegeAdbPairingClient(
    private val endpoint: PrivilegeAdbEndpoint,
    private val pairCode: String,
    private val key: PrivilegeAdbKey,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private val peerInfo = PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, key.adbPublicKey)
    private var pairingContext: PrivilegeAdbPairingCryptoContext? = null

    fun start(): Boolean {
        val context = setupTlsConnection()
        return doExchangeMsgs(context) && doExchangePeerInfo(context)
    }

    private fun setupTlsConnection(): PrivilegeAdbPairingCryptoContext {
        val plainSocket = Socket()
        plainSocket.connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MILLIS)
        plainSocket.soTimeout = READ_TIMEOUT_MILLIS
        socket = plainSocket
        socket.tcpNoDelay = true
        val sslSocket = key.sslContext.socketFactory
            .createSocket(socket, endpoint.host, endpoint.port, true) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MILLIS
        sslSocket.startHandshake()

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(
            sslSocket,
            ADB_PAIRING_EXPORTED_KEY_LABEL,
            null,
            EXPORTED_KEY_SIZE,
        )
        val passwordBytes = ByteArray(pairCodeBytes.size + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        return PrivilegeAdbPairingCryptoContext.createClient(passwordBytes).also {
            pairingContext = it
        }
    }

    private fun doExchangeMsgs(context: PrivilegeAdbPairingCryptoContext): Boolean {
        val msg = context.msg
        writeHeader(createHeader(PairingPacketHeader.TYPE_SPAKE2_MSG, msg.size), msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.TYPE_SPAKE2_MSG) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        return context.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(context: PrivilegeAdbPairingCryptoContext): Boolean {
        val buffer = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buffer)

        val outbuf = context.encrypt(buffer.array()) ?: return false
        writeHeader(createHeader(PairingPacketHeader.TYPE_PEER_INFO, outbuf.size), outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.TYPE_PEER_INFO) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        val decrypted = context.decrypt(theirMessage) ?: throw PrivilegeAdbException("Invalid ADB pairing code")
        if (decrypted.size != MAX_PEER_INFO_SIZE) return false
        PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        return true
    }

    private fun createHeader(type: Byte, payloadSize: Int): PairingPacketHeader =
        PairingPacketHeader(CURRENT_KEY_HEADER_VERSION, type, payloadSize)

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(PAIRING_PACKET_HEADER_SIZE)
        inputStream.readFully(bytes)
        return PairingPacketHeader.readFrom(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN))
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(PAIRING_PACKET_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)
        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
    }

    override fun close() {
        runCatching { inputStream.close() }
        runCatching { outputStream.close() }
        runCatching { socket.close() }
        pairingContext?.destroy()
        pairingContext = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 5_000
    }
}
