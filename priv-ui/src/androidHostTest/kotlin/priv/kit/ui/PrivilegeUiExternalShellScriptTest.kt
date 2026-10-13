package priv.kit.ui

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowProcess
import priv.kit.core.Privilege
import priv.kit.core.internal.runtime.PrivilegeContext
import priv.kit.ui.runtime.loadManualShellCommand
import priv.kit.ui.state.PrivilegeUiViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = PrivilegeUiExternalShellScriptTest.RecordingApplication::class)
class PrivilegeUiExternalShellScriptTest {
    private lateinit var application: RecordingApplication
    private lateinit var script: File

    @Before
    fun prepareRuntime() {
        ShadowProcess.setUid(10_000)
        application = RuntimeEnvironment.getApplication() as RecordingApplication
        application.applicationInfo.apply {
            sourceDir = "/data/app/example/base.apk"
            splitSourceDirs = null
            nativeLibraryDir = File(application.cacheDir, "native/arm64").apply {
                mkdirs()
                File(this, "libprivkitstarter.so").writeBytes(byteArrayOf(1))
            }.path.replace('\\', '/')
        }
        application.externalDirectory = File(application.cacheDir, "external/${application.packageName}/files")
        script = File(application.externalDirectory, "start.sh")
        PrivilegeContext.install(application)
    }

    @Test
    fun preparesAndRefreshesActualFileOnCallerThreadWithoutUiConfig() {
        val callerThread = Thread.currentThread()
        assertFalse(script.exists())

        val preparedFile = PrivilegeUi.prepareExternalShellScript("files/start.sh")

        assertEquals(script, preparedFile)
        assertTrue(preparedFile!!.isAbsolute)
        assertEquals("#!/system/bin/sh\n${Privilege.nativeStarterCommand}\n", script.readText())
        assertEquals(1, application.externalFilesCalls)
        assertSame(callerThread, application.externalFilesThread)

        script.writeText("outdated command")
        assertEquals(script, PrivilegeUi.prepareExternalShellScript("files/start.sh"))
        assertEquals("#!/system/bin/sh\n${Privilege.nativeStarterCommand}\n", script.readText())
        assertEquals(2, application.externalFilesCalls)
    }

    @Test
    fun uiReusesScriptPreparedAtApplicationStartup() = runBlocking {
        val config = PrivilegeUiConfig(manualShellScriptPath = "files/start.sh")
        assertEquals(script, PrivilegeUi.prepareExternalShellScript(config.manualShellScriptPath))
        assertTrue(script.setLastModified(1_000_000L))
        val unchangedTimestamp = script.lastModified()

        repeat(2) {
            val store = PrivilegeUiViewModelStore(application, config)
            store.loadManualShellCommand()

            assertEquals(
                "adb shell sh /sdcard/Android/data/${application.packageName}/files/start.sh",
                store.state.value.manualShellCommandLine,
            )
            assertEquals(unchangedTimestamp, script.lastModified())
            assertTrue(store.state.value.startupLogLines.isEmpty())
        }
    }

    @Test
    fun nullPathSkipsExternalStorage() {
        assertNull(PrivilegeUi.prepareExternalShellScript(null))

        assertEquals(0, application.externalFilesCalls)
        assertFalse(script.exists())
    }

    @Test
    fun invalidPathFailsBeforeExternalStorageAccess() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivilegeUi.prepareExternalShellScript("../start.sh")
        }
        assertEquals(0, application.externalFilesCalls)
        assertFalse(script.exists())
    }

    @Test
    fun nonzeroUserSkipsPreparation() {
        ShadowProcess.setUid(1_010_000)
        application.applicationInfo.uid = 1_010_000

        assertNull(
            PrivilegeUi.prepareExternalShellScript("files/start.sh"),
        )

        assertEquals(0, application.externalFilesCalls)
        assertFalse(script.exists())
    }

    @Test
    fun writeFailureReturnsNullAndLeavesExistingFileUntouched() {
        application.externalDirectory!!.apply {
            parentFile!!.mkdirs()
            writeText("existing file blocking the directory")
        }

        assertNull(
            PrivilegeUi.prepareExternalShellScript("files/start.sh"),
        )

        assertFalse(script.exists())
        assertEquals("existing file blocking the directory", application.externalDirectory!!.readText())
    }

    @Test
    fun uiPropagatesUnexpectedScriptPreparationFailures() {
        val failure = IllegalStateException("Unexpected storage provider failure")
        application.externalFilesFailure = failure
        val store = PrivilegeUiViewModelStore(application, PrivilegeUiConfig(manualShellScriptPath = "files/start.sh"))

        val thrown = assertThrows(IllegalStateException::class.java) {
            runBlocking { store.loadManualShellCommand() }
        }

        assertEquals(failure.message, thrown.message)
        assertTrue(store.state.value.startupLogLines.isEmpty())
    }

    @Test
    fun uiUsesIoAndSdcardAliasOrFallsBackAfterInitialWriteFailure() = runBlocking {
        val callerThread = Thread.currentThread()
        val config = PrivilegeUiConfig(manualShellScriptPath = "files/start.sh")
        val store = PrivilegeUiViewModelStore(application, config)
        val shortCommand = "adb shell sh /sdcard/Android/data/${application.packageName}/files/start.sh"
        assertEquals(shortCommand, store.state.value.manualShellCommandLine)

        store.loadManualShellCommand()

        assertEquals(shortCommand, store.state.value.manualShellCommandLine)
        assertNotSame(callerThread, application.externalFilesThread)
        assertTrue(script.isFile)

        val blockedDirectory = File(application.cacheDir, "blocked").apply { writeText("keep") }
        application.externalDirectory = File(blockedDirectory, "files")
        val failedStore = PrivilegeUiViewModelStore(application, config)
        assertEquals(shortCommand, failedStore.state.value.manualShellCommandLine)
        failedStore.loadManualShellCommand()

        assertEquals("adb shell ${Privilege.nativeStarterCommand}", failedStore.state.value.manualShellCommandLine)
        assertTrue(failedStore.state.value.startupLogLines.isNotEmpty())
        assertEquals("keep", blockedDirectory.readText())
    }

    class RecordingApplication : Application() {
        var externalDirectory: File? = null
        var externalFilesCalls = 0
        var externalFilesThread: Thread? = null
        var externalFilesFailure: RuntimeException? = null

        override fun getExternalFilesDir(type: String?): File? {
            externalFilesCalls += 1
            externalFilesThread = Thread.currentThread()
            externalFilesFailure?.let { throw it }
            return externalDirectory
        }
    }
}
