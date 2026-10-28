# priv-ui

Compose Multiplatform authorization UI under `priv.kit.ui`. Android uses Core; JVM and WasmJS support previews.

| API | Purpose |
| --- | --- |
| `PrivilegeScaffold` | Android authorization page |
| `PrivilegePreviewScaffold` | Interactive simulation |
| `PrivilegeScreen` | Host-controlled multiplatform page |
| `PrivilegeUiViewModel` | Extensible Android controller |
| `PrivilegeUiConfig` | Startup modes, polling, notification pairing, and providers |
| `PrivilegeUi.desiredEnabled` | Automatic-recovery setting |
| `PrivilegeUi.startSilently(...)` | Restart using the last successful method |
| `PrivilegeUi.prepareExternalShellScript(...)` | Prepare the optional manual-start script |

## Integration

Use the host's Material 3 theme. Apps calling `viewModel()` must add `lifecycle-viewmodel-compose`.
Create config and external providers once per process for both foreground and silent startup. Use application-scoped state and keep provider IDs stable across upgrades.

```kotlin
val privilegeUiConfig by lazy {
    PrivilegeUiConfig(
        externalStartProviders = listOf(shizukuProvider),
    )
}

class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(application, privilegeUiConfig)

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

The page handles Root, ADB, manual and external startup, pairing, permissions, and restart confirmation. Other app features observe `Privilege.serverState`.

Pass `onShowToast = { message -> appToast(message) }` to `PrivilegeScaffold` to route all transient page messages, including action feedback and controller errors, to the host application's toast. The callback runs on the UI thread and receives localized text. Omit it to keep the built-in Snackbar behavior.

## Lifecycle

Foreground and silent starts are mutually exclusive. Silent startup uses the last successful method and existing authorization. A root manager may still show its own prompt.

Passive checks follow the selected tab and host lifecycle. See the [UI guide](../priv-website/guide/priv-ui.md) for recovery, notification IDs, and permission handling.

## Resources and preview

`commonMain` contains pages, presentation state, and English/Chinese strings; `androidMain` adapts Core, permissions, and lifecycle. RemoteViews XML is used only for notifications.

Previews simulate startup, pairing, and confirmations. Any six-digit pairing code works; Copy uses the host clipboard. Closing the host ends the session.

See the [UI guide](../priv-website/guide/priv-ui.md) for configuration, scripts, custom pages, and tutorial callbacks; see [architecture](../docs/architecture.md) for internals.
