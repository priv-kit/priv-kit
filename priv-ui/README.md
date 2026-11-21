# priv-ui

Use `priv-ui` with your own components and theme. It does not depend on Material 3.

```kotlin
implementation("io.github.priv-kit:priv-ui:$privKitVersion")
```

```kotlin
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.*

@OptIn(ExperimentalPrivilegeUiApi::class)
@Composable
fun CustomStartupPage() {
    val vm: PrivilegeUiViewModel = viewModel()
    val context = LocalContext.current

    PrivilegeUiHost(
        viewModel = vm,
        onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    ) { ui ->
        AppStartupContent(ui)
    }
}
```

Implement `AppStartupContent` with your own UI: read `ui.state`, invoke `ui.actions`, and handle pairing input and confirmations. The Host supplies lifecycle and platform bindings.

- [Integration guide](../priv-website/guide/priv-ui.md#custom-rendering)
- [Default Material 3 page](../priv-ui-m3/README.md)
