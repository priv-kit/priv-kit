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
): (Boolean, Boolean, Boolean) -> Unit {
    val options = mutableStateOf(Triple(dark, useLegacyPackaging, adbRestricted))
    configureWebResources { resourcePathMapping(resourceUrl) }
    ComposeViewport(viewportContainer = asElement(container)) {
        PrivilegePlaygroundApp(dark = options.value.first, useLegacyPackaging = options.value.second,
            adbRestricted = options.value.third)
    }
    return { nextDark, nextLegacyPackaging, nextAdbRestricted ->
        options.value = Triple(nextDark, nextLegacyPackaging, nextAdbRestricted)
    }
}

private fun asElement(container: JsAny): Element = js("container")

fun main() = Unit
