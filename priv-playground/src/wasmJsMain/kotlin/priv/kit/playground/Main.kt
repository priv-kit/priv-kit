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
): (Boolean, Boolean) -> Unit {
    val options = mutableStateOf(dark to useLegacyPackaging)
    configureWebResources { resourcePathMapping(resourceUrl) }
    ComposeViewport(viewportContainer = asElement(container)) {
        PrivilegePlaygroundApp(dark = options.value.first, useLegacyPackaging = options.value.second)
    }
    return { nextDark, nextLegacyPackaging -> options.value = nextDark to nextLegacyPackaging }
}

private fun asElement(container: JsAny): Element = js("container")

fun main() = Unit
