package priv.kit.adb

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

            val status = client(server.localPort).use { it.checkAuthorization() }

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

            val status = client(server.localPort).use { it.requestAuthorization() }

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
                client(server.localPort, socketReadTimeoutMillis = 200).use { it.requestAuthorization() }
            }
            assertTrue(publicKeyReceived.await(1, TimeUnit.SECONDS))
            serverThread.join(1_500L)
        }
    }

    private fun client(
        port: Int,
        socketReadTimeoutMillis: Int = 1_000,
    ): PrivilegeAdbClient =
        PrivilegeAdbClient(
            port = port,
            signAuthToken = { "signature".toByteArray() },
            adbPublicKey = "public-key".toByteArray(),
            socketReadTimeoutMillis = socketReadTimeoutMillis,
        )

    private fun DataInputStream.readAdbMessage(): PrivilegeAdbMessage {
        val header = ByteArray(PrivilegeAdbMessage.HEADER_LENGTH)
        readFully(header)
        val headerBuffer = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        headerBuffer.int
        headerBuffer.int
        headerBuffer.int
        val dataLength = headerBuffer.int
        val data = ByteArray(dataLength)
        if (dataLength > 0) {
            readFully(data)
        }
        return PrivilegeAdbMessage.fromByteArray(header + data)
    }

    private fun DataOutputStream.writeAuthToken(token: String) {
        writeAdbMessage(
            PrivilegeAdbMessage(
                command = PrivilegeAdbProtocol.A_AUTH,
                arg0 = PrivilegeAdbProtocol.ADB_AUTH_TOKEN,
                arg1 = 0,
                data = token.toByteArray(),
            ),
        )
    }

    private fun DataOutputStream.writeAdbMessage(message: PrivilegeAdbMessage) {
        write(message.toByteArray())
        flush()
    }
}
