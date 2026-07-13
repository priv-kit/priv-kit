# priv-ui

Optional Jetpack Compose UI helper module for Priv Kit.

Namespace and package root: `priv.kit.ui`.

This module provides a reusable embedded page for runtime authorization status and authorization entry points.

Public entry points:

- `PrivilegeScaffold`, the root Compose page.
- `PrivilegeUiViewModel`, an `open` `AndroidViewModel` state manager that callers may subclass.
- `PrivilegeUiConfig`, used to enable startup modes, polling intervals, and external start providers.

`PrivilegeScaffold` consumes the caller's Compose `MaterialTheme` colors. Apps that need light, dark, dynamic, or branded authorization UI should wrap it in their own Material 3 theme instead of configuring colors through `PrivilegeUiConfig`.

`PrivilegeUiViewModel.startWirelessAdbStatusPolling()`, `startTcpModeStatusPolling()`,
and `startExternalStartStatusPolling()` return `AutoCloseable` polling handles. Close
the returned handle when the host no longer needs that polling request; the paired
`stop*Polling()` methods still force-stop all active handles for that polling type.

Internal Android components:

- `PrivilegeAdbPairingService` is manifest-merged for the built-in notification pairing flow and is not a public app-call API.
- Notification pairing may use Android `RemoteViews` XML layouts for notification-only controls. These layouts are not page UI and must not be inflated by app screens.

The UI covers ordinary user-facing authorization only:

- Authorization method tabs that show one method at a time.
- Root startup.
- Manual shell command copy.
- ADB authorization, including Wireless ADB pairing, notification pairing, status polling, startup, and optional TCP reuse.
- Managed Wireless Debugging status when runtime can temporarily enable Wireless Debugging through `WRITE_SECURE_SETTINGS`.
- External startup through app-provided `PrivilegeUiExternalStartProvider` implementations, with status refreshed on foreground resume and while the External tab is selected.
- Realtime startup transcript for Root, ADB, and streaming external startup providers.
- Service started/not-started status.

It does not include built-in Shizuku integration, app-owned service management, stop-service controls, package management, input injection, settings, app-ops, a general diagnostic log console, or other high-level Android system operation UI. Shizuku-style support belongs in the app or an optional integration as a `PrivilegeUiExternalStartProvider`; the external privileged process can call `PrivilegeExternalStartup.runInCurrentProcess(...)`, while the main process can bridge returned source/message pairs into the UI through `PrivilegeExternalStartup.createReceiver(...)`.

`PrivilegeUiConfig.enableManagedWirelessAdb` controls whether the UI treats runtime-managed Wireless Debugging as a startup path. The UI only displays status and passes startup options; it does not write `Settings.Global` itself. If the merged app manifest no longer declares `WRITE_SECURE_SETTINGS` (for example through `tools:node="remove"`), the managed Wireless Debugging row is hidden and the UI stops passing that startup path.

Basic usage:

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    PrivilegeUiConfig(
        externalStartProviders = listOf(myShizukuProvider),
    ),
)

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
    onBackClick = {
        // Return to the host app page.
    },
    onHelpClick = {
        // Open app-owned authorization help.
    },
    onConnected = {
        // Return to the app page that needs privileged access.
    },
    onNotificationPermissionRequired = {
        // Request android.permission.POST_NOTIFICATIONS on Android 13+,
        // then call viewModel.handleNotificationPermissionResult(granted).
    },
    onLocalNetworkPermissionRequired = { permission ->
        // Request the supplied local-network permission on Android 17+
        // after Wireless debugging falls back to a LAN endpoint.
    },
)
```

All static UI and notification text lives in `src/main/res/values/strings.xml` with the `priv_ui_` prefix so apps can override or localize it.
