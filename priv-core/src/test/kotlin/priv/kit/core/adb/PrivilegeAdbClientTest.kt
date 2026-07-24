package priv.kit.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class PrivilegeAdbClientTest {
    @Test
    fun checkAuthorizationReturnsUnauthorizedWithoutSendingPublicKey() {
        val publicKeyReceived = AtomicBoolean(false)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-auth-check", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAuthToken("token")
                    input.readAdbMessage()
                    output.writeAuthToken("token-again")
                    runCatching {
                        val next = input.readAdbMessage()
                        publicKeyReceived.set(
                            next.command == PrivilegeAdbProtocol.A_AUTH &&
                                next.arg0 == PrivilegeAdbProtocol.ADB_AUTH_RSAPUBLICKEY,
                        )
                    }
                }
            }

            val status = client(
                port = server.localPort,
                socketReadTimeoutMillis = 1_000,
                signAuthToken = { "signature".toByteArray() },
            ).use {
                it.checkAuthorization(output = null)
            }

            assertEquals(PrivilegeAdbAuthorizationStatus.UNAUTHORIZED, status)
            serverThread.join(1_500L)
            assertFalse(publicKeyReceived.get())
        }
    }

    @Test
    fun requestAuthorizationSendsPublicKeyAndReturnsAuthorizedAfterConnect() {
        val publicKeyReceived = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-auth-request", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAuthToken("token")
                    input.readAdbMessage()
                    output.writeAuthToken("token-again")
                    val publicKey = input.readAdbMessage()
                    if (publicKey.command == PrivilegeAdbProtocol.A_AUTH &&
                        publicKey.arg0 == PrivilegeAdbProtocol.ADB_AUTH_RSAPUBLICKEY
                    ) {
                        publicKeyReceived.countDown()
                    }
                    output.writeAdbMessage(
                        PrivilegeAdbMessage(
                            command = PrivilegeAdbProtocol.A_CNXN,
                            arg0 = PrivilegeAdbProtocol.A_VERSION,
                            arg1 = PrivilegeAdbProtocol.A_MAXDATA,
                            data = "device::".toByteArray(),
                        ),
                    )
                }
            }

            val status = client(
                port = server.localPort,
                socketReadTimeoutMillis = 1_000,
                signAuthToken = { "signature".toByteArray() },
            ).use {
                it.requestAuthorization(output = null)
            }

            assertEquals(PrivilegeAdbAuthorizationStatus.AUTHORIZED, status)
            assertTrue(publicKeyReceived.await(1, TimeUnit.SECONDS))
            serverThread.join(1_500L)
        }
    }

    @Test
    fun requestAuthorizationTimesOutWhenPublicKeyAuthorizationNeverCompletes() {
        val publicKeyReceived = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-auth-timeout", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAuthToken("token")
                    input.readAdbMessage()
                    output.writeAuthToken("token-again")
                    input.readAdbMessage()
                    publicKeyReceived.countDown()
                    Thread.sleep(1_000L)
                }
            }

            assertThrows(SocketTimeoutException::class.java) {
                client(
                    port = server.localPort,
                    socketReadTimeoutMillis = 200,
                    signAuthToken = { "signature".toByteArray() },
                ).use {
                    it.requestAuthorization(output = null)
                }
            }
            assertTrue(publicKeyReceived.await(1, TimeUnit.SECONDS))
            serverThread.join(1_500L)
        }
    }

    @Test
    fun commandsCanReuseOneAuthorizedConnection() {
        val localIds = mutableListOf<Int>()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-reused-connection", isDaemon = true) {
                server.accept().use { socket ->
                    socket.soTimeout = 2_000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAdbMessage(
                        PrivilegeAdbMessage(
                            command = PrivilegeAdbProtocol.A_CNXN,
                            arg0 = PrivilegeAdbProtocol.A_VERSION,
                            arg1 = PrivilegeAdbProtocol.A_MAXDATA,
                            data = "device::".toByteArray(),
                        ),
                    )

                    repeat(2) { index ->
                        val open = input.readAdbMessage()
                        localIds += open.arg0
                        val remoteId = index + 1
                        output.writeAdbMessage(
                            PrivilegeAdbMessage(
                                command = PrivilegeAdbProtocol.A_OKAY,
                                arg0 = remoteId,
                                arg1 = open.arg0,
                                data = null,
                            ),
                        )
                        output.writeAdbMessage(
                            PrivilegeAdbMessage(
                                command = PrivilegeAdbProtocol.A_CLSE,
                                arg0 = remoteId,
                                arg1 = open.arg0,
                                data = null,
                            ),
                        )
                        input.readAdbMessage()
                    }
                }
            }

            client(
                port = server.localPort,
                socketReadTimeoutMillis = 1_000,
                signAuthToken = { "signature".toByteArray() },
            ).use { client ->
                val output = PrivilegeAdbOutput()

                client.connect(output)
                client.command("shell:true", output)
                client.command("shell:true", output)
            }

            serverThread.join(1_500L)
            assertEquals(listOf(1, 2), localIds)
        }
    }

    @Test
    fun rejectsPayloadLargerThanAdvertisedMaximum() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-oversized-payload", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAdbHeader(
                        command = PrivilegeAdbProtocol.A_CNXN,
                        arg0 = PrivilegeAdbProtocol.A_VERSION,
                        arg1 = PrivilegeAdbProtocol.A_MAXDATA,
                        dataLength = PrivilegeAdbProtocol.A_MAXDATA + 1,
                    )
                }
            }

            val exception = assertThrows(PrivilegeAdbException::class.java) {
                client(
                    port = server.localPort,
                    socketReadTimeoutMillis = 1_000,
                    signAuthToken = { "signature".toByteArray() },
                ).use { it.connect(output = null) }
            }

            assertTrue(exception.message.orEmpty().contains("Invalid ADB payload length"))
            serverThread.join(1_500L)
        }
    }

    @Test
    fun rejectsAuthTokenWithUnexpectedLengthBeforeSigning() {
        val signingAttempted = AtomicBoolean(false)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-invalid-auth-token", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAdbMessage(
                        PrivilegeAdbMessage(
                            command = PrivilegeAdbProtocol.A_AUTH,
                            arg0 = PrivilegeAdbProtocol.ADB_AUTH_TOKEN,
                            arg1 = 0,
                            data = ByteArray(PrivilegeAdbProtocol.ADB_AUTH_TOKEN_LENGTH - 1),
                        ),
                    )
                }
            }

            val exception = assertThrows(PrivilegeAdbException::class.java) {
                client(
                    port = server.localPort,
                    socketReadTimeoutMillis = 1_000,
                    signAuthToken = {
                        signingAttempted.set(true)
                        "signature".toByteArray()
                    },
                ).use { it.connect(output = null) }
            }

            assertFalse(signingAttempted.get())
            assertTrue(exception.message.orEmpty().contains("ADB auth token must be"))
            serverThread.join(1_500L)
        }
    }

    private fun client(
        port: Int,
        socketReadTimeoutMillis: Int,
        signAuthToken: (ByteArray) -> ByteArray,
    ): PrivilegeAdbClient =
        PrivilegeAdbClient(
            port = port,
            signAuthToken = signAuthToken,
            adbPublicKey = "public-key".toByteArray(),
            socketReadTimeoutMillis = socketReadTimeoutMillis,
        )

    private fun DataInputStream.readAdbMessage(): PrivilegeAdbMessage {
        val header = ByteArray(PrivilegeAdbMessage.HEADER_LENGTH)
        readFully(header)
        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = headerBuffer.int
        val arg0 = headerBuffer.int
        val arg1 = headerBuffer.int
        val dataLength = headerBuffer.int
        val checksum = headerBuffer.int
        val magic = headerBuffer.int
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { readFully(it) }
        } else {
            null
        }
        return PrivilegeAdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
    }

    private fun DataOutputStream.writeAuthToken(token: String) {
        val tokenBytes = token.toByteArray()
        writeAdbMessage(
            PrivilegeAdbMessage(
                command = PrivilegeAdbProtocol.A_AUTH,
                arg0 = PrivilegeAdbProtocol.ADB_AUTH_TOKEN,
                arg1 = 0,
                data = ByteArray(PrivilegeAdbProtocol.ADB_AUTH_TOKEN_LENGTH) { index ->
                    tokenBytes[index % tokenBytes.size]
                },
            ),
        )
    }

    private fun DataOutputStream.writeAdbMessage(message: PrivilegeAdbMessage) {
        write(message.toByteArray())
        flush()
    }

    private fun DataOutputStream.writeAdbHeader(
        command: Int,
        arg0: Int,
        arg1: Int,
        dataLength: Int,
    ) {
        val header = ByteBuffer.allocate(PrivilegeAdbMessage.HEADER_LENGTH)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                putInt(command)
                putInt(arg0)
                putInt(arg1)
                putInt(dataLength)
                putInt(0)
                putInt(command xor -0x1)
            }
        write(header.array())
        flush()
    }
}
