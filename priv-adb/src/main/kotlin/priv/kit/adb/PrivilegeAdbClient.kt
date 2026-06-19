package priv.kit.adb

import android.os.Build
import android.util.Log
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

internal class PrivilegeAdbClient(
    private val host: String,
    private val port: Int,
    private val key: PrivilegeAdbKey,
) : Closeable {
    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream

    private val inputStream: DataInputStream
        get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream: DataOutputStream
        get() = if (useTls) tlsOutputStream else plainOutputStream

    fun connect(output: PrivilegeAdbOutput? = null) {
        output.diagnostic("Connecting to $host:$port")
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        newSocket.tcpNoDelay = true
        socket = newSocket
        plainInputStream = DataInputStream(newSocket.getInputStream())
        plainOutputStream = DataOutputStream(newSocket.getOutputStream())
        output.diagnostic("Socket connected to $host:$port")

        write(PrivilegeAdbProtocol.A_CNXN, PrivilegeAdbProtocol.A_VERSION, PrivilegeAdbProtocol.A_MAXDATA, "host::")
        var message = read()
        output.diagnostic("Initial ADB response: ${message.toStringShort()}")
        when (message.command) {
            PrivilegeAdbProtocol.A_STLS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    privilegeAdbError("ADB TLS is not supported before Android 10")
                }
                output.diagnostic("ADB requested TLS upgrade")
                write(PrivilegeAdbProtocol.A_STLS, PrivilegeAdbProtocol.A_STLS_VERSION, 0)
                tlsSocket = key.sslContext.socketFactory.createSocket(newSocket, host, port, true) as SSLSocket
                tlsSocket.startHandshake()
                output.diagnostic("ADB TLS handshake succeeded")
                tlsInputStream = DataInputStream(tlsSocket.inputStream)
                tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
                useTls = true
                message = read()
                output.diagnostic("Post-TLS ADB response: ${message.toStringShort()}")
            }
            PrivilegeAdbProtocol.A_AUTH -> {
                if (message.arg0 != PrivilegeAdbProtocol.ADB_AUTH_TOKEN) {
                    privilegeAdbError("ADB auth did not provide a token")
                }
                output.diagnostic("ADB requested RSA auth signature")
                write(
                    PrivilegeAdbProtocol.A_AUTH,
                    PrivilegeAdbProtocol.ADB_AUTH_SIGNATURE,
                    0,
                    key.sign(message.data),
                )
                message = read()
                output.diagnostic("ADB auth response after signature: ${message.toStringShort()}")
                if (message.command != PrivilegeAdbProtocol.A_CNXN) {
                    output.diagnostic("ADB requested public key; device may need authorization")
                    write(
                        PrivilegeAdbProtocol.A_AUTH,
                        PrivilegeAdbProtocol.ADB_AUTH_RSAPUBLICKEY,
                        0,
                        key.adbPublicKey,
                    )
                    message = read()
                    output.diagnostic("ADB auth response after public key: ${message.toStringShort()}")
                }
            }
        }

        if (message.command != PrivilegeAdbProtocol.A_CNXN) {
            privilegeAdbError("ADB connection did not finish with CNXN")
        }
        output.diagnostic("ADB connection ready on $host:$port, tls=$useTls")
    }

    fun command(
        command: String,
        output: PrivilegeAdbOutput,
    ) {
        val localId = 1
        output.diagnostic("Opening ADB service ${command.toDiagnosticName()}")
        write(PrivilegeAdbProtocol.A_OPEN, localId, 0, command)

        var message = read()
        output.diagnostic("ADB OPEN response: ${message.toStringShort()}")
        when (message.command) {
            PrivilegeAdbProtocol.A_OKAY -> {
                output.diagnostic("ADB stream opened")
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        PrivilegeAdbProtocol.A_WRTE -> {
                            val data = message.data
                            output.diagnostic("ADB stream WRTE bytes=${message.dataLength}")
                            if (message.dataLength > 0 && data != null) {
                                output.append("adb", String(data))
                            }
                            write(PrivilegeAdbProtocol.A_OKAY, localId, remoteId)
                        }
                        PrivilegeAdbProtocol.A_CLSE -> {
                            output.diagnostic("ADB stream closed by remote")
                            write(PrivilegeAdbProtocol.A_CLSE, localId, remoteId)
                            break
                        }
                        else -> privilegeAdbError("ADB stream returned an unexpected message")
                    }
                }
            }
            PrivilegeAdbProtocol.A_CLSE -> {
                output.diagnostic("ADB service closed immediately")
                write(PrivilegeAdbProtocol.A_CLSE, localId, message.arg0)
            }
            else -> privilegeAdbError("ADB command did not return OKAY or CLSE")
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) {
        write(PrivilegeAdbMessage(command, arg0, arg1, data))
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) {
        write(PrivilegeAdbMessage(command, arg0, arg1, data))
    }

    private fun write(message: PrivilegeAdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): PrivilegeAdbMessage {
        val buffer = ByteBuffer.allocate(PrivilegeAdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        inputStream.readFully(buffer.array(), 0, PrivilegeAdbMessage.HEADER_LENGTH)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it, 0, dataLength) }
        } else {
            null
        }
        return PrivilegeAdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
            .also {
                it.validateOrThrow()
                Log.d(TAG, "read ${it.toStringShort()}")
            }
    }

    override fun close() {
        runCatching { plainInputStream.close() }
        runCatching { plainOutputStream.close() }
        runCatching { socket.close() }
        if (useTls) {
            runCatching { tlsInputStream.close() }
            runCatching { tlsOutputStream.close() }
            runCatching { tlsSocket.close() }
        }
    }

    companion object {
        private const val TAG = "PrivKitAdb"
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
    }
}

private fun PrivilegeAdbOutput?.diagnostic(text: String) {
    Log.d("PrivKitAdb", text)
    this?.append("diag", text)
}

private fun String.toDiagnosticName(): String =
    when {
        startsWith("shell:") -> "shell:<redacted>, length=$length"
        else -> this
    }
