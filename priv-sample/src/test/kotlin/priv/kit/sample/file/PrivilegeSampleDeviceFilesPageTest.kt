package priv.kit.sample.file

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeSampleDeviceFilesPageTest {
    @Test
    fun directoryBackLeavesPageOnlyWhenThereIsNoParent() {
        assertNull(directoryBackTarget(currentDirectory = "/", parentDirectory = null))
    }

    @Test
    fun directoryBackNavigatesToRootParent() {
        assertEquals(
            "/",
            directoryBackTarget(currentDirectory = "/storage", parentDirectory = "/"),
        )
    }

    @Test
    fun directoryBackNavigatesToNonRootParent() {
        assertEquals(
            "/storage",
            directoryBackTarget(
                currentDirectory = "/storage/emulated",
                parentDirectory = "/storage",
            ),
        )
    }

    @Test
    fun loadingKeepsDirectoryControlsVisuallyEnabled() {
        val controls = PrivilegeSampleDeviceFilesState(
            serverRunning = true,
            isLoadingDirectory = true,
        ).deviceDirectoryControls()

        assertEquals(
            DeviceDirectoryControls(
                enabled = true,
                directoryReadOnly = true,
            ),
            controls,
        )
    }

    @Test
    fun disconnectedDirectoryControlsRemainDisabled() {
        val controls = PrivilegeSampleDeviceFilesState(
            serverRunning = false,
            isLoadingDirectory = false,
        ).deviceDirectoryControls()

        assertEquals(
            DeviceDirectoryControls(
                enabled = false,
                directoryReadOnly = false,
            ),
            controls,
        )
    }

    @Test
    fun hexRowsReadPreviewBytesDirectly() {
        val bytes = "%PDF-1.5".toByteArray()

        assertEquals(1, bytes.hexRowCount())
        assertTrue(bytes.formatHexRow(0).startsWith("00000000  25 50 44 46 2D 31 2E 35"))
        assertTrue(bytes.formatHexRow(0).endsWith("|%PDF-1.5        |"))
    }

    @Test
    fun hexRowsPreserveBytesAcrossRowBoundary() {
        val bytes = ByteArray(17) { index -> index.toByte() }

        assertEquals(2, bytes.hexRowCount())
        assertTrue(bytes.formatHexRow(1).startsWith("00000010  10"))
        assertTrue(bytes.formatHexRow(1).endsWith("|.               |"))
        assertEquals(0, byteArrayOf().hexRowCount())
    }
}
