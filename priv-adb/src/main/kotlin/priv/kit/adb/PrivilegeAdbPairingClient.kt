package priv.kit.adb

import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val CURRENT_KEY_HEADER_VERSION = 1.toByte()
private const val MIN_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_SUPPORTED_KEY_HEADER_VERSION = 1.toByte()
private const val MAX_PEER_INFO_SIZE = 8192
private const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
private const val EXPORTED_KEY_LABEL = "adb-label\u0000"
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
            if (version < MIN_SUPPORTED_KEY_HEADER_VERSION || version > MAX_SUPPORTED_KEY_HEADER_VERSION) return null
            if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) return null
            if (payload <= 0 || payload > MAX_PAYLOAD_SIZE) return null
            return PairingPacketHeader(version, type, payload)
        }
    }
}

internal class PrivilegeAdbPairingContext private constructor(private val nativePtr: Long) {
    val msg: ByteArray = nativeMsg(nativePtr)

    fun initCipher(theirMsg: ByteArray): Boolean = nativeInitCipher(nativePtr, theirMsg)
    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)
    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)
    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            System.loadLibrary("privkitadb")
        }

        fun create(password: ByteArray): PrivilegeAdbPairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PrivilegeAdbPairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

internal class PrivilegeAdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: PrivilegeAdbKey,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream
    private val peerInfo = PeerInfo(PeerInfo.ADB_RSA_PUB_KEY, key.adbPublicKey)
    private var pairingContext: PrivilegeAdbPairingContext? = null

    fun start(): Boolean {
        setupTlsConnection()
        return doExchangeMsgs() && doExchangePeerInfo()
    }

    private fun setupTlsConnection() {
        socket = Socket(host, port)
        socket.tcpNoDelay = true
        val sslSocket = key.sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = PrivilegeAdbHiddenApis.exportKeyingMaterial(
            socket = sslSocket,
            label = EXPORTED_KEY_LABEL,
            context = null,
            length = EXPORTED_KEY_SIZE,
        )
        val passwordBytes = ByteArray(pairCodeBytes.size + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        pairingContext = checkNotNull(PrivilegeAdbPairingContext.create(passwordBytes)) {
            "Unable to create ADB pairing context"
        }
    }

    private fun doExchangeMsgs(): Boolean {
        val context = checkNotNull(pairingContext)
        val msg = context.msg
        writeHeader(createHeader(PairingPacketHeader.TYPE_SPAKE2_MSG, msg.size), msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.TYPE_SPAKE2_MSG) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        return context.initCipher(theirMessage)
    }

    private fun doExchangePeerInfo(): Boolean {
        val context = checkNotNull(pairingContext)
        val buffer = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buffer)

        val outbuf = context.encrypt(buffer.array()) ?: return false
        writeHeader(createHeader(PairingPacketHeader.TYPE_PEER_INFO, outbuf.size), outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.TYPE_PEER_INFO) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)
        val decrypted = context.decrypt(theirMessage) ?: throw PrivilegeAdbInvalidPairingCodeException()
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
}
