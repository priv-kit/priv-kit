# priv-ui

Optional Jetpack Compose UI helper module for Priv Kit.

Namespace and package root: `priv.kit.ui`.

This module provides a reusable embedded page for runtime authorization status and authorization entry points.

Public entry points:

- `PrivilegeScaffold`, the root Compose page.
- `PrivilegeUiViewModel`, an `open` `AndroidViewModel` state manager that callers may subclass.
- `PrivilegeUiConfig`, used to enable startup modes, polling intervals, and external start providers.
- `PrivilegeAdbPairingService`, a foreground service that accepts Wireless ADB pairing codes through notification `RemoteInput`.

The UI covers ordinary user-facing authorization only:

- Authorization method tabs that show one method at a time.
- Root startup.
- Manual shell command copy.
- ADB authorization, including Wireless ADB pairing, notification pairing, status polling, startup, and optional TCP reuse.
- External startup through app-provided `PrivilegeUiExternalStartProvider` implementations, with status refreshed on foreground resume and while the External tab is selected.
- Realtime startup transcript for Root, ADB, and streaming external startup providers.
- Service started/not-started status.

It does not include built-in Shizuku integration, app-owned service management, stop-service controls, package management, input injection, settings, app-ops, a general diagnostic log console, or other high-level Android system operation UI. Shizuku-style support belongs in the app or an optional integration as a `PrivilegeUiExternalStartProvider`; the external privileged process can call `PrivilegeExternalStartup.runInCurrentProcess(...)`, while the main process can bridge returned source/message pairs into the UI through `PrivilegeExternalStartup.createReceiver(...)`.

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
)
```

All static UI and notification text lives in `src/main/res/values/strings.xml` with the `priv_ui_` prefix so apps can override or localize it.
