package priv.kit.ui.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrivilegeUiStartMethodStoreTest {
    private val application = RuntimeEnvironment.getApplication()
    private val directory = File(application.filesDir, ".priv-kit")
    private val file = File(directory, "ui-start-method")
    private val temporaryFile = File(directory, "ui-start-method.tmp")
    private val store = PrivilegeUiStartMethodStore(application)

    @Before
    fun setUp() {
        file.delete()
        temporaryFile.delete()
    }

    @After
    fun tearDown() {
        file.delete()
        temporaryFile.delete()
    }

    @Test
    fun parsesKnownMethodIds() {
        assertEquals(PrivilegeUiStartMethod.Root, PrivilegeUiStartMethod.parse("root"))
        assertEquals(
            PrivilegeUiStartMethod.AdbWireless,
            PrivilegeUiStartMethod.parse("adb-wireless"),
        )
        assertEquals(
            PrivilegeUiStartMethod.AdbTcpip,
            PrivilegeUiStartMethod.parse("adb-tcpip"),
        )
        assertEquals(
            PrivilegeUiStartMethod.External("provider:child"),
            PrivilegeUiStartMethod.parse("external:provider:child"),
        )
        assertEquals(
            PrivilegeUiStartMethod.External(""),
            PrivilegeUiStartMethod.parse("external:"),
        )
    }

    @Test
    fun constructsStableMethodIds() {
        assertEquals("root", PrivilegeUiStartMethod.Root.methodId)
        assertEquals("adb-wireless", PrivilegeUiStartMethod.AdbWireless.methodId)
        assertEquals("adb-tcpip", PrivilegeUiStartMethod.AdbTcpip.methodId)
        assertEquals(
            "external: provider ",
            PrivilegeUiStartMethod.External(" provider ").methodId,
        )
    }

    @Test
    fun rejectsEmptyAndUnknownMethodIdsWithoutTrimmingKnownIds() {
        assertNull(PrivilegeUiStartMethod.parse(""))
        assertNull(PrivilegeUiStartMethod.parse("adb"))
        assertNull(PrivilegeUiStartMethod.parse("root\n"))
        assertNull(PrivilegeUiStartMethod.parse(" root"))
    }

    @Test
    fun missingFileReturnsNull() {
        assertNull(store.read())
    }

    @Test
    fun writesSingleUtf8MethodIdUnderPrivKitDirectory() {
        val method = PrivilegeUiStartMethod.External("外部:提供者")

        store.write(method)

        assertTrue(file.isFile)
        assertEquals(
            method.methodId,
            String(file.readBytes(), StandardCharsets.UTF_8),
        )
        assertFalse(temporaryFile.exists())
        assertEquals(method, store.read())
    }

    @Test
    fun replacesExistingMethodId() {
        store.write(PrivilegeUiStartMethod.Root)

        store.write(PrivilegeUiStartMethod.AdbTcpip)

        assertEquals(PrivilegeUiStartMethod.AdbTcpip, store.read())
        assertEquals("adb-tcpip", file.readText(StandardCharsets.UTF_8))
        assertFalse(temporaryFile.exists())
    }

    @Test
    fun unknownPersistedValueReturnsNullWithoutChangingFile() {
        directory.mkdirs()
        file.writeText("root\n", StandardCharsets.UTF_8)

        assertNull(store.read())
        assertEquals("root\n", file.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun concurrentStoreInstancesNeverLeaveATornMethodId() {
        val methods = listOf(
            PrivilegeUiStartMethod.Root,
            PrivilegeUiStartMethod.AdbWireless,
            PrivilegeUiStartMethod.AdbTcpip,
            PrivilegeUiStartMethod.External("provider-with-a-long-identifier"),
        )
        val executor = Executors.newFixedThreadPool(methods.size)
        try {
            val writes = methods.map { method ->
                executor.submit {
                    val independentStore = PrivilegeUiStartMethodStore(application)
                    repeat(25) {
                        independentStore.write(method)
                    }
                }
            }
            writes.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(store.read() in methods)
        assertTrue(file.readText(StandardCharsets.UTF_8) in methods.map { it.methodId })
        assertFalse(temporaryFile.exists())
    }
}
