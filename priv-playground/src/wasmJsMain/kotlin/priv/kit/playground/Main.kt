@file:OptIn(
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    kotlin.js.ExperimentalJsExport::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
    org.jetbrains.compose.resources.ExperimentalResourceApi::class,
)

package priv.kit.playground

import androidx.compose.ui.window.ComposeViewport
import androidx.compose.runtime.mutableStateOf
import org.jetbrains.compose.resources.configureWebResources
import org.w3c.dom.Element
import kotlin.js.JsAny

/** The website owns the host element and removes its children when leaving the page. */
@JsExport
fun renderPrivilegePlayground(
    container: JsAny,
    resourceUrl: (String) -> String,
    dark: Boolean,
    useLegacyPackaging: Boolean,
    adbRestricted: Boolean,
    batteryOptimizationExempt: Boolean,
    localNetworkPermissionGranted: Boolean,
    onPermissionsChanged: (Boolean, Boolean) -> Unit,
): (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit {
    val options = mutableStateOf(PlaygroundOptions(dark, useLegacyPackaging, adbRestricted, batteryOptimizationExempt, localNetworkPermissionGranted))
    configureWebResources { resourcePathMapping(resourceUrl) }
    ComposeViewport(viewportContainer = asElement(container)) {
        PrivilegePlaygroundApp(dark = options.value.dark, useLegacyPackaging = options.value.useLegacyPackaging,
            adbRestricted = options.value.adbRestricted, batteryOptimizationExempt = options.value.batteryOptimizationExempt,
            localNetworkPermissionGranted = options.value.localNetworkPermissionGranted, onPermissionsChanged = onPermissionsChanged)
    }
    return { nextDark, nextLegacyPackaging, nextAdbRestricted, nextBatteryExempt, nextNetworkGranted ->
        options.value = PlaygroundOptions(nextDark, nextLegacyPackaging, nextAdbRestricted, nextBatteryExempt, nextNetworkGranted)
    }
}

private data class PlaygroundOptions(
    val dark: Boolean,
    val useLegacyPackaging: Boolean,
    val adbRestricted: Boolean,
    val batteryOptimizationExempt: Boolean,
    val localNetworkPermissionGranted: Boolean,
)

private fun asElement(container: JsAny): Element = js("container")

fun main() = Unit
