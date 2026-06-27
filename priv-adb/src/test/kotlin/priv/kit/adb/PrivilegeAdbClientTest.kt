package priv.kit.adb

import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PrivilegeAdbClientTest {
    @Test
    fun connectTimesOutWhenPublicKeyAuthorizationNeverCompletes() {
        val publicKeyReceived = CountDownLatch(1)
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val serverThread = thread(name = "fake-adb-auth-timeout", isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())

                    input.readAdbMessage()
                    output.writeAdbMessage(
                        PrivilegeAdbMessage(
                            command = PrivilegeAdbProtocol.A_AUTH,
                            arg0 = PrivilegeAdbProtocol.ADB_AUTH_TOKEN,
                            arg1 = 0,
                            data = "token".toByteArray(),
                        ),
                    )
                    input.readAdbMessage()
                    output.writeAdbMessage(
                        PrivilegeAdbMessage(
                            command = PrivilegeAdbProtocol.A_AUTH,
                            arg0 = PrivilegeAdbProtocol.ADB_AUTH_TOKEN,
                            arg1 = 0,
                            data = "token-again".toByteArray(),
                        ),
                    )
                    input.readAdbMessage()
                    publicKeyReceived.countDown()
                    Thread.sleep(1_000L)
                }
            }

            val client = PrivilegeAdbClient(
                host = "127.0.0.1",
                port = server.localPort,
                signAuthToken = { "signature".toByteArray() },
                adbPublicKey = "public-key".toByteArray(),
                socketReadTimeoutMillis = 200,
            )

            client.use {
                assertThrows(SocketTimeoutException::class.java) {
                    it.connect()
                }
            }
            assertTrue(publicKeyReceived.await(1, TimeUnit.SECONDS))
            serverThread.join(1_500L)
        }
    }

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

    private fun DataOutputStream.writeAdbMessage(message: PrivilegeAdbMessage) {
        write(message.toByteArray())
        flush()
    }
}
