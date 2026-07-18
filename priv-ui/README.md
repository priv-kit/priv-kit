# priv-ui

Optional Jetpack Compose UI helper module for Priv Kit.

Namespace and package root: `priv.kit.ui`.

This module provides a reusable embedded page for runtime authorization status and authorization entry points.

Public entry points:

- `PrivilegeScaffold`, the root Compose page.
- `PrivilegeUiViewModel`, an `open` `AndroidViewModel` state manager that callers may subclass.
- `PrivilegeUiConfig`, used to enable startup modes, polling intervals, and external start providers.

Process-wide owner-death behavior remains a runtime concern. Configure it through
`PrivilegeConfig` before starting a server; `PrivilegeUiConfig` does not mirror or
override that global runtime state.

Compose Foundation and Material 3 are API dependencies because `PrivilegeScaffold`
exposes Material 3 Scaffold slots and parameters. The lifecycle Compose adapter remains
an implementation dependency; host apps using `viewModel()` as shown below should
declare their own `androidx.lifecycle:lifecycle-viewmodel-compose` dependency.

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
- Contextual battery-optimization guidance above the ADB panel when the host app is not exempt, refreshed whenever the host returns to the foreground.
- Managed Wireless Debugging status when runtime can temporarily enable Wireless Debugging through `WRITE_SECURE_SETTINGS`.
- Static-TCP status that distinguishes a missing port configuration from a configured port whose ADB listener is not running.
- External startup through app-provided `PrivilegeUiExternalStartProvider` implementations, with status refreshed on foreground resume and while the External tab is selected.
- Realtime startup transcript for Root, ADB, and streaming external startup providers.
- Service started/not-started status.

Battery-optimization guidance directly opens Android's exemption confirmation for the
host package and rechecks the result when the page returns to the foreground. `priv-ui`
manifest-merges the permission required by Android for this direct confirmation:

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

Apps distributed through Google Play must ensure that direct exemption is appropriate for
their core functionality and complies with current power-management policy. A host that
does not want the direct request can remove the merged permission in its own manifest:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission
        android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
        tools:node="remove" />
</manifest>
```

When the permission is removed or the direct confirmation is unavailable, the UI falls
back to Android's battery optimization list and then the host application's details page.

It does not include built-in Shizuku integration, app-owned service management, stop-service controls, package management, input injection, settings, app-ops, a general diagnostic log console, or other high-level Android system operation UI. Shizuku-style support belongs in the app or an optional integration as a `PrivilegeUiExternalStartProvider`; the external privileged process can call `PrivilegeExternalStartup.runInCurrentProcess(...)`, while the main process can bridge returned source/message pairs into the UI through `PrivilegeExternalStartup.createReceiver(...)`.

`PrivilegeUiConfig.enableManagedWirelessAdb` controls whether the UI treats runtime-managed Wireless Debugging as a startup path. The UI only displays status and passes startup options; it does not write `Settings.Global` itself. If the merged app manifest no longer declares `WRITE_SECURE_SETTINGS` (for example through `tools:node="remove"`), the managed Wireless Debugging row is hidden and the UI stops passing that startup path.

When the user explicitly starts a configured static-TCP endpoint whose listener is unavailable, the UI asks `priv-runtime` to prepare that endpoint before falling back to Wireless Debugging. The runtime writes `ADB_ENABLED=1` only when `WRITE_SECURE_SETTINGS` is declared and granted, retries the listener, and never enables `adb_wifi_enabled` for this recovery path. Passive UI polling remains read-only.

The top service action silently tries configured workflows in the order supplied by the UI before reporting a generic failure. If a Root or ADB command may have created a detached server but its Binder handshake does not complete, fallback stops and reports the generic failure because that server may still arrive later. An External request remains in the same start session while the runner waits for its Binder connection or timeout; a timeout also stops fallback because the requested server may still arrive later. Every start uses the same cooperative cancellation model: the first cancellation request changes the owning controls from Cancel to a disabled Cancelling state, Root and ADB observe cancellation at their internal checkpoints, and an External provider call with no checkpoints remains in Cancelling until that call returns. Other startup controls stay disabled while a start is running or cancelling.

Basic usage:

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    PrivilegeUiConfig(
        externalStartProviders = listOf(myShizukuProvider),
    ),
) {
    override fun onBackClick(): Boolean {
        // Update app navigation state or emit a host event.
        return true
    }

    override fun onConnected(serverInfo: PrivilegeServerInfo) {
        // Update the app after a new privileged-server connection.
    }
}

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

`PrivilegeScaffold` exposes the nested Material 3 Scaffold's `topBar`, `bottomBar`,
`snackbarHost`, `floatingActionButton`, `floatingActionButtonPosition`, `containerColor`,
`contentColor`, and `contentWindowInsets` parameters. Its authorization top bar and
snackbar host remain the defaults, while Scaffold colors and insets follow Material 3.
A custom `snackbarHost` receives the internal `SnackbarHostState` so ViewModel feedback
continues to use the supplied host.

`PrivilegeScaffold` owns its Activity Result launchers for notification and local-network
permissions and returns the notification result to the ViewModel. Host subclasses may
override `onBackClick()` and `onConnected(...)`. These hooks
should update host state or emit host events; a ViewModel must not retain an `Activity`,
`NavController`, Compose state holder, or Activity Result launcher. Returning `false`
from `onBackClick()` delegates to the system back dispatcher. Hosts that need custom
top-bar actions should supply their own `topBar`.

All static UI and notification text lives in `src/main/res/values/strings.xml` with the `priv_ui_` prefix so apps can override or localize it.
