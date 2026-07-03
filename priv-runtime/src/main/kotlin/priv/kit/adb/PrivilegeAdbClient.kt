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
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

internal interface PrivilegeAdbConnection : Closeable {
    fun connect(output: PrivilegeAdbOutput? = null)
    fun keepAlive(output: PrivilegeAdbOutput)
}

internal class PrivilegeAdbClient private constructor(
    private val port: Int,
    private val signAuthToken: (ByteArray?) -> ByteArray,
    private val adbPublicKey: ByteArray,
    private val sslContextProvider: () -> SSLContext,
    private val socketReadTimeoutMillis: Int,
) : PrivilegeAdbConnection {
    constructor(
        port: Int,
        key: PrivilegeAdbKey,
        socketReadTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
    ) : this(
        port = port,
        signAuthToken = key::sign,
        adbPublicKey = key.adbPublicKey,
        sslContextProvider = { key.sslContext },
        socketReadTimeoutMillis = socketReadTimeoutMillis,
    )

    internal constructor(
        port: Int,
        signAuthToken: (ByteArray?) -> ByteArray,
        adbPublicKey: ByteArray,
        socketReadTimeoutMillis: Int,
    ) : this(
        port = port,
        signAuthToken = signAuthToken,
        adbPublicKey = adbPublicKey,
        sslContextProvider = { SSLContext.getDefault() },
        socketReadTimeoutMillis = socketReadTimeoutMillis,
    )

    init {
        require(socketReadTimeoutMillis > 0) { "socketReadTimeoutMillis must be positive" }
    }

    private lateinit var socket: Socket
    private lateinit var plainInputStream: DataInputStream
    private lateinit var plainOutputStream: DataOutputStream

    private var useTls = false
    private lateinit var tlsSocket: SSLSocket
    private lateinit var tlsInputStream: DataInputStream
    private lateinit var tlsOutputStream: DataOutputStream
    private var nextLocalId = 1

    private val inputStream: DataInputStream
        get() = if (useTls) tlsInputStream else plainInputStream
    private val outputStream: DataOutputStream
        get() = if (useTls) tlsOutputStream else plainOutputStream

    override fun connect(output: PrivilegeAdbOutput?) {
        connectSocket(output)
        val status = authenticate(
            output = output,
            submitPublicKey = false,
        )
        if (status != PrivilegeAdbAuthorizationStatus.AUTHORIZED) {
            privilegeAdbError("ADB key is not authorized")
        }
        output.diagnostic("ADB connection ready on $PRIVILEGE_ADB_LOCAL_HOST:$port, tls=$useTls")
    }

    override fun keepAlive(output: PrivilegeAdbOutput) {
        command(KEEP_ALIVE_COMMAND, output)
    }

    fun checkAuthorization(output: PrivilegeAdbOutput? = null): PrivilegeAdbAuthorizationStatus {
        connectSocket(output)
        return authenticate(
            output = output,
            submitPublicKey = false,
        )
    }

    fun requestAuthorization(output: PrivilegeAdbOutput? = null): PrivilegeAdbAuthorizationStatus {
        connectSocket(output)
        return authenticate(
            output = output,
            submitPublicKey = true,
        )
    }

    private fun connectSocket(output: PrivilegeAdbOutput? = null) {
        output.diagnostic("Connecting to $PRIVILEGE_ADB_LOCAL_HOST:$port")
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(PRIVILEGE_ADB_LOCAL_HOST, port), CONNECT_TIMEOUT_MILLIS)
        newSocket.tcpNoDelay = true
        newSocket.soTimeout = socketReadTimeoutMillis
        socket = newSocket
        plainInputStream = DataInputStream(newSocket.getInputStream())
        plainOutputStream = DataOutputStream(newSocket.getOutputStream())
        output.diagnostic("Socket connected to $PRIVILEGE_ADB_LOCAL_HOST:$port")
    }

    private fun authenticate(
        output: PrivilegeAdbOutput?,
        submitPublicKey: Boolean,
    ): PrivilegeAdbAuthorizationStatus {
        write(PrivilegeAdbProtocol.A_CNXN, PrivilegeAdbProtocol.A_VERSION, PrivilegeAdbProtocol.A_MAXDATA, "host::")
        var message = read()
        output.diagnostic("Initial ADB response: ${message.toStringShort()}")
        when (message.command) {
            PrivilegeAdbProtocol.A_CNXN -> return PrivilegeAdbAuthorizationStatus.AUTHORIZED
            PrivilegeAdbProtocol.A_STLS -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    privilegeAdbError("ADB TLS is not supported before Android 10")
                }
                output.diagnostic("ADB requested TLS upgrade")
                write(PrivilegeAdbProtocol.A_STLS, PrivilegeAdbProtocol.A_STLS_VERSION, 0)
                tlsSocket = sslContextProvider()
                    .socketFactory
                    .createSocket(socket, PRIVILEGE_ADB_LOCAL_HOST, port, true) as SSLSocket
                tlsSocket.soTimeout = socketReadTimeoutMillis
                tlsSocket.startHandshake()
                output.diagnostic("ADB TLS handshake succeeded")
                tlsInputStream = DataInputStream(tlsSocket.inputStream)
                tlsOutputStream = DataOutputStream(tlsSocket.outputStream)
                useTls = true
                message = read()
                output.diagnostic("Post-TLS ADB response: ${message.toStringShort()}")
                if (message.command == PrivilegeAdbProtocol.A_CNXN) {
                    return PrivilegeAdbAuthorizationStatus.AUTHORIZED
                }
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
                    signAuthToken(message.data),
                )
                message = read()
                output.diagnostic("ADB auth response after signature: ${message.toStringShort()}")
                if (message.command == PrivilegeAdbProtocol.A_CNXN) {
                    return PrivilegeAdbAuthorizationStatus.AUTHORIZED
                }
                if (message.command == PrivilegeAdbProtocol.A_AUTH &&
                    message.arg0 == PrivilegeAdbProtocol.ADB_AUTH_TOKEN
                ) {
                    if (!submitPublicKey) {
                        output.diagnostic("ADB key is not authorized")
                        return PrivilegeAdbAuthorizationStatus.UNAUTHORIZED
                    }
                    output.diagnostic("ADB requested public key; device may need authorization")
                    write(
                        PrivilegeAdbProtocol.A_AUTH,
                        PrivilegeAdbProtocol.ADB_AUTH_RSAPUBLICKEY,
                        0,
                        adbPublicKey,
                    )
                    message = read()
                    output.diagnostic("ADB auth response after public key: ${message.toStringShort()}")
                    if (message.command == PrivilegeAdbProtocol.A_CNXN) {
                        return PrivilegeAdbAuthorizationStatus.AUTHORIZED
                    }
                    if (message.command == PrivilegeAdbProtocol.A_AUTH &&
                        message.arg0 == PrivilegeAdbProtocol.ADB_AUTH_TOKEN
                    ) {
                        return PrivilegeAdbAuthorizationStatus.UNAUTHORIZED
                    }
                }
            }
        }

        privilegeAdbError("ADB connection did not finish with CNXN")
    }

    fun command(
        command: String,
        output: PrivilegeAdbOutput,
    ) {
        val localId = nextLocalId()
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

    private fun nextLocalId(): Int {
        val localId = nextLocalId
        nextLocalId = if (nextLocalId == Int.MAX_VALUE) 1 else nextLocalId + 1
        return localId
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
        logDebug("write ${message.toStringShort()}")
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
                logDebug("read ${it.toStringShort()}")
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
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 5_000
        private const val KEEP_ALIVE_COMMAND = "shell:true"
    }
}

private fun PrivilegeAdbOutput?.diagnostic(text: String) {
    logDebug(text)
    this?.append("diag", text)
}

private fun logDebug(text: String) {
    runCatching {
        Log.d("PrivKitAdb", text)
    }
}

private fun String.toDiagnosticName(): String =
    when {
        startsWith("shell:") -> "shell:<redacted>, length=$length"
        else -> this
    }
