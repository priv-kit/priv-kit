package priv.kit.core.adb

import android.net.nsd.NsdManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import priv.kit.core.PrivilegeStartupException
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeAdbTcpManagerTest {
    @After
    fun clearProperties() {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "")
        setSystemProperty(PERSIST_ADB_TCP_PORT, "")
    }

    @Test
    fun switchToTcpSkipsCommandWhenTargetPortIsAlreadyActive() = runBlocking {
        setSystemProperty(SERVICE_ADB_TCP_PORT, "5555")
        val manager = manager(
            keyProvider = { error("key should not be loaded when TCP port is already active") },
            nsdManagerProvider = { error("NSD should not be used when TCP port is already active") },
        )

        val result = manager.switchToTcp(
            tcpPort = 5555,
            options = null,
        )

        assertEquals(5555, result.port)
        assertTrue(result.outputText.contains("ADB TCP port 5555 is already active"))
    }

    @Test
    fun openTcpAuthorizationCheckSessionWrapsKeyFailureInPublicStartupException() {
        val keyFailure = IllegalStateException("key storage is unavailable")
        val manager = manager(
            keyProvider = { throw keyFailure },
            nsdManagerProvider = { error("NSD should not be used when loading the ADB key") },
        )

        val exception = assertThrows(PrivilegeStartupException::class.java) {
            manager.openTcpAuthorizationCheckSession(
                tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
            )
        }

        assertEquals(PrivilegeStartupException::class.java, exception.javaClass)
        assertEquals("Failed to open ADB TCP authorization check session", exception.message)
        assertSame(keyFailure, exception.cause?.cause)
    }

    @Test
    fun tcpControlFallsBackOnceAfterStaticConnectFailure() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { fallbackServer ->
            val serverFailure = AtomicReference<Throwable?>()
            val serverThread = thread(name = "fake-adb-control-fallback", isDaemon = true) {
                runCatching {
                    fallbackServer.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        input.readAdbMessage()
                        output.writeConnected()
                        val open = input.readAdbMessage()
                        output.writeAdbMessage(
                            PrivilegeAdbMessage(
                                command = PrivilegeAdbProtocol.A_OKAY,
                                arg0 = 1,
                                arg1 = open.arg0,
                                data = null,
                            ),
                        )
                    }
                }.onFailure(serverFailure::set)
            }
            val staticPort = unusedTcpPort()

            val result = manager(
                keyProvider = ::fakeAdbKey,
                nsdManagerProvider = { error("NSD should not be used with an explicit fallback port") },
            ).stopTcp(
                tcpPort = staticPort,
                options = PrivilegeAdbConnectionOptions(port = fallbackServer.localPort),
            )

            serverThread.join(2_000L)
            serverFailure.get()?.let { throw AssertionError("Fake ADB server failed", it) }
            assertEquals(staticPort, result.port)
            assertTrue(result.outputText.contains("Static ADB TCP connection failed"))
        }
    }

    @Test
    fun tcpControlDoesNotReplayAfterCommandDispatchFailure() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { staticServer ->
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { fallbackServer ->
                val fallbackAccepted = AtomicBoolean(false)
                val staticServerThread = thread(name = "fake-adb-control-rejected", isDaemon = true) {
                    staticServer.accept().use { socket ->
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        input.readAdbMessage()
                        output.writeConnected()
                        val open = input.readAdbMessage()
                        output.writeAdbMessage(
                            PrivilegeAdbMessage(
                                command = PrivilegeAdbProtocol.A_CLSE,
                                arg0 = 1,
                                arg1 = open.arg0,
                                data = null,
                            ),
                        )
                    }
                }
                fallbackServer.soTimeout = 1_000
                val fallbackServerThread = thread(name = "fake-adb-control-unused", isDaemon = true) {
                    try {
                        fallbackServer.accept().use { fallbackAccepted.set(true) }
                    } catch (_: SocketTimeoutException) {
                    }
                }

                val failure = runCatching {
                    manager(
                        keyProvider = ::fakeAdbKey,
                        nsdManagerProvider = { error("NSD should not be used with an explicit fallback port") },
                    ).restartTcp(
                        tcpPort = staticServer.localPort,
                        options = PrivilegeAdbConnectionOptions(port = fallbackServer.localPort),
                    )
                }.exceptionOrNull()

                staticServerThread.join(2_000L)
                fallbackServerThread.join(2_000L)
                assertTrue(failure is PrivilegeStartupException)
                assertFalse(fallbackAccepted.get())
            }
        }
    }

    private fun manager(
        keyProvider: () -> PrivilegeAdbKey,
        nsdManagerProvider: () -> NsdManager,
    ): PrivilegeAdbTcpManager {
        val identityProvider = PrivilegeAdbIdentityProvider(
            identity = PrivilegeAdbIdentity.default(
                deviceName = PrivilegeAdbIdentity.DEFAULT_DEVICE_NAME,
            ),
            keyProvider = keyProvider,
        )
        val wirelessDebuggingControllerProvider = { fakeWirelessDebuggingController() }
        val endpointResolver = PrivilegeAdbEndpointResolver(
            nsdManagerProvider = nsdManagerProvider,
            wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
        )
        return PrivilegeAdbTcpManager(
            identityProvider = identityProvider,
            endpointResolver = endpointResolver,
            wirelessDebuggingControllerProvider = wirelessDebuggingControllerProvider,
        )
    }

    private fun fakeWirelessDebuggingController(): PrivilegeAdbWirelessDebuggingController =
        object : PrivilegeAdbWirelessDebuggingController {
            override fun status(): PrivilegeAdbWirelessDebuggingControlStatus =
                PrivilegeAdbWirelessDebuggingControlStatus(
                    supported = true,
                    permissionDeclared = true,
                    permissionGranted = false,
                    wirelessDebuggingEnabled = false,
                    canManage = false,
                )

            override fun enableAdb() = Unit

            override fun prepareAdb() = Unit

            override fun setWirelessDebuggingEnabled(enabled: Boolean) = Unit
        }

    private fun fakeAdbKey(): PrivilegeAdbKey {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply {
            isAccessible = true
        }
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        val material = unsafe.allocateInstance(PrivilegeAdbKeyMaterial::class.java)
            as PrivilegeAdbKeyMaterial
        PrivilegeAdbKeyMaterial::class.java
            .getDeclaredField("adbPublicKeyPayload\$delegate")
            .apply { isAccessible = true }
            .set(material, lazyOf(ByteArray(524) { 1 }))
        PrivilegeAdbKeyMaterial::class.java
            .getDeclaredField("adbPublicKeyFingerprint\$delegate")
            .apply { isAccessible = true }
            .set(material, lazyOf("test-fingerprint"))
        return PrivilegeAdbKey(material, "test")
    }

    private fun unusedTcpPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun DataInputStream.readAdbMessage(): PrivilegeAdbMessage {
        val header = ByteArray(PrivilegeAdbMessage.HEADER_LENGTH)
        readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) ByteArray(dataLength).also(::readFully) else null
        return PrivilegeAdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
    }

    private fun DataOutputStream.writeConnected() {
        writeAdbMessage(
            PrivilegeAdbMessage(
                command = PrivilegeAdbProtocol.A_CNXN,
                arg0 = PrivilegeAdbProtocol.A_VERSION,
                arg1 = PrivilegeAdbProtocol.A_MAXDATA,
                data = "device::".toByteArray(),
            ),
        )
    }

    private fun DataOutputStream.writeAdbMessage(message: PrivilegeAdbMessage) {
        write(message.toByteArray())
        flush()
    }

    private fun setSystemProperty(key: String, value: String) {
        systemPropertiesClass
            .getDeclaredMethod("set", String::class.java, String::class.java)
            .invoke(null, key, value)
    }

    private companion object {
        private const val SERVICE_ADB_TCP_PORT = "service.adb.tcp.port"
        private const val PERSIST_ADB_TCP_PORT = "persist.adb.tcp.port"
        private val systemPropertiesClass = Class.forName("android.os.SystemProperties")
    }
}
