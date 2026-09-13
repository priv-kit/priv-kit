package priv.kit.playground

import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.test.*
import org.jetbrains.skia.Image
import java.io.File
import java.util.Locale
import kotlin.test.Test
import priv.kit.ui.PrivilegePreviewScaffold
import androidx.compose.material3.MaterialTheme

@OptIn(ExperimentalTestApi::class)
class PrivilegePlaygroundTest {
    @Test
    fun statusCardCancelInterruptsStartupWithoutLateConnection() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            runSkikoComposeUiTest(size = Size(480f, 820f)) {
                setContent { MaterialTheme { PrivilegePreviewScaffold() } }
                onNodeWithText("Root").performClick().assertIsSelected()
                mainClock.autoAdvance = false
                onNodeWithContentDescription("Start service").performClick()
                mainClock.advanceTimeByFrame()
                onNodeWithContentDescription("Cancel").assertIsEnabled().performClick()
                // Advance beyond the original 1.2-second startup completion.
                mainClock.advanceTimeBy(2_000)
                onNodeWithText("Not started").assertExists()
                onNodeWithContentDescription("Start service").assertIsEnabled()

                // Cancellation must also release the controls for a subsequent start.
                mainClock.autoAdvance = true
                onNodeWithContentDescription("Start service").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Started").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Source: Root").assertExists()
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun defaultPreviewStartsTheSimulation() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            runSkikoComposeUiTest(size = Size(480f, 820f)) {
                setContent { MaterialTheme { PrivilegePreviewScaffold() } }
                onNodeWithText("Not started").assertExists()
                onNodeWithText("Root").performClick().assertIsSelected()
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Started").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Source: Root").assertExists()
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun simulatedStartupPairingAndConfirmationDialogsAreInteractive() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            runSkikoComposeUiTest(size = Size(480f, 820f)) {
                setContent { PrivilegePlaygroundApp(dark = false) }
                onNodeWithText("ADB").assertIsSelected()
                onNodeWithText("Root").performClick().assertIsSelected()
                onNodeWithText("Start").performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Started").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Source: Root").assertExists()
                onNodeWithText("Start").performClick()
                onNodeWithText("Restart Privileged Server?").assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithText("Started").assertExists()
                onNodeWithContentDescription("Stop service").performClick()
                onNodeWithText("Stop service?").assertExists()
                onNodeWithText("Stop").performClick()
                onNodeWithText("Not started").assertExists()
                onNodeWithText("Manual").performClick().assertIsSelected()
                onNodeWithText("Copy this command and run it from a computer connected to this device, manual startup cannot restart itself after the service is closed, another startup method is recommended").assertExists()
                onNodeWithText("Copy").assertIsEnabled()
                onNode(hasText("External") and hasClickAction()).performClick().assertIsSelected()
                onNodeWithText("Start").performClick()
                onNodeWithText("Allow the simulated provider to authorize and start the service? This does not grant any real permissions.").assertExists()
                onNodeWithText("Cancel").performClick()
                onNodeWithText("ADB").performClick().assertIsSelected()
                onNodeWithText("Pair").performClick()
                onNodeWithText("Pair wireless debugging").assertExists()
                onNodeWithText("Pairing service found. Enter the pairing code").assertExists()
                onNode(hasSetTextAction()).performTextInput("123456")
                onNode(hasText("Pair") and hasAnyAncestor(isDialog())).performClick()
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("Pair wireless debugging").fetchSemanticsNodes().isEmpty() }
                val output = File("build/reports/playground/desktop-en.png")
                output.parentFile.mkdirs()
                output.writeBytes(Image.makeFromBitmap(onRoot().captureToImage().asSkiaBitmap()).encodeToData()!!.bytes)
            }
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}
