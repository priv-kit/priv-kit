# priv-ui

Optional UI and UI-originated startup helper module for Priv Kit.

Namespace and package root: `priv.kit.ui`.

This module provides a reusable embedded page for runtime authorization status and authorization entry points, plus a headless entry point that can replay the last successful foreground start.

Public entry points:

- `PrivilegeScaffold`, the root Compose page.
- `PrivilegeUiViewModel`, an `open` `AndroidViewModel` state manager that callers may subclass.
- `PrivilegeUiConfig`, used to enable startup modes, polling intervals, and external start providers.
- `PrivilegeUi.startSilently(...)`, a suspend function that can run without an `Activity` or ViewModel.

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
- A connected-server warning above the authorization method tabs when the device restricts ADB shell permissions. The status is checked after each connection and whenever the host returns to the foreground; Root servers skip the permission check.

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

When the user explicitly starts a configured static-TCP endpoint whose listener is unavailable, the UI asks `priv-core` to prepare that endpoint before falling back to Wireless Debugging. The runtime writes `ADB_ENABLED=1` only when `WRITE_SECURE_SETTINGS` is declared and granted, retries the listener, and never enables `adb_wifi_enabled` for this recovery path. Passive UI polling remains read-only.

The foreground service action calls `PrivilegeUiViewModel.startInteractive()` and tries configured workflows in the order supplied by the UI before reporting a generic failure. If a Root or ADB command may have created a detached server but its Binder handshake does not complete, fallback stops and reports the generic failure because that server may still arrive later. An External request remains in the same start session while the runner waits for its Binder connection or timeout; a timeout also stops fallback because the requested server may still arrive later. Every foreground start uses the same cooperative cancellation model: the first cancellation request changes the owning controls from Cancel to a disabled Cancelling state, Root and ADB observe cancellation at their internal checkpoints, and an External provider call with no checkpoints remains in Cancelling until that call returns. Other startup controls stay disabled while a start is running or cancelling.

## Foreground, silent, and owner-reconnect startup

When a foreground start owned by `PrivilegeUiViewModel` commits a launch method and receives the matching `INITIAL_LAUNCH` Binder connection for that runtime operation before cancellation, `priv-ui` records the exact winning method in `filesDir/.priv-kit/ui-start-method`. The file contains one raw UTF-8 method ID, not JSON or a preference schema:

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

Silent starts, retained-server `OWNER_RECONNECT` handshakes, already-connected servers, manual shell starts outside the matching foreground operation, cancelled starts, and failed starts do not replace this value.

After obtaining the process-local start gate, `PrivilegeUi.startSilently(context, config)` first returns an already-connected or ready server, if present. During the runtime's app-start reconciliation window it then gives a retained server time to complete owner reconnect before reading the saved method and committing a new launch. If no connection wins, it attempts only that exact method. It does not initialize Compose, create a ViewModel, require an `Activity`, fall back to another method, show a snackbar, invoke Android permission launchers, or update the saved method. Without an existing connection, missing history, an unknown or disabled method, missing authorization, startup failure, and timeout all return `null`.

Foreground and silent startup are mutually exclusive through one process-local gate. Accepted foreground startup effects—including runtime start/stop, TCP changes, pairing, permission requests, and external authorization calls—retain nestable leases scoped to one foreground ViewModel owner until their owned work completes. A different ViewModel cannot join that owner's nesting, and `startSilently(...)` returns `null` while any foreground lease remains. The two UI entry points use first-acquired ownership without queuing or preemption.

Owner reconnect participates through the runtime arbiter rather than the UI gate. An already-connected/ready server wins first; a retained server whose reconnect arrives during preflight wins before Root, ADB, or external launch side effects are committed. After a foreground or silent start commits its runtime lease, a late `OWNER_RECONNECT` is rejected and the new launch continues. Permission requests are additionally scoped to their active `PrivilegeScaffold` host and are discarded when the last host leaves, so a detached launcher cannot keep the gate occupied or replay a stale prompt. While silent startup owns the gate, the built-in UI disables new side-effecting entries. After silent startup releases the gate, each existing ViewModel re-reads runtime state before enabling those entries again, so a just-connected server cannot race with another foreground start. A multi-process app must initialize and invoke Priv Kit startup from only one designated app process; calling `startSilently(...)` during every process's `Application` initialization can start duplicate attempts.

Silent method behavior is deliberately narrow:

- Wireless ADB may temporarily enable Wireless Debugging when `enableManagedWirelessAdb` is enabled and the app already has the runtime capability required to manage it. It never starts pairing, and returns `null` when the saved ADB identity is not paired. If the platform requires `ACCESS_LOCAL_NETWORK` and it has not already been granted, the method returns `null` without requesting it.
- Static TCP uses only the current `config.tcpPort`, with discovery and Wireless Debugging fallback disabled. An unavailable listener or unauthorized ADB key returns `null`; the authorization request flow is not invoked.
- External startup resolves the exact saved provider ID from the supplied config, requires its current `snapshot()` to report `canStart`, and never calls `requestAuthorization()`. Provider IDs are persistent keys and should remain stable across app upgrades. The snapshot and start call share `startTimeoutMillis`; blocking providers should respond to thread interruption so cancellation and timeout can finish promptly.
- Root startup reuses the existing Root path. `priv-ui` does not request Android permissions, but a root manager may still display its own authorization UI if its previous grant is no longer valid.

Construct external providers and `PrivilegeUiConfig` once at application scope, then pass the same config instance to both entry points. An `Application` property is one straightforward option:

```kotlin
class App : Application() {
    val privilegeUiConfig by lazy {
        PrivilegeUiConfig(
            externalStartProviders = listOf(myShizukuProvider),
        )
    }
}

// Safe to call from a background coroutine before any Activity or UI is created.
val serverInfo = PrivilegeUi.startSilently(
    context = app,
    config = app.privilegeUiConfig,
)
```

An `object` or top-level lazy property is also suitable when it obtains only application-scoped dependencies. External providers must not retain an `Activity`, and their `snapshot()` implementation should remain a read-only status check.

Basic usage:

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    (application as App).privilegeUiConfig,
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
override `onBackClick()`, `onConnected(...)`, and
`onNotificationPermissionSettingsRequested(...)`. These hooks should update host state,
emit host events, or customize notification-settings navigation; a ViewModel must not retain
the settings hook's `Context`, an `Activity`, `NavController`, Compose state holder, or
Activity Result launcher. Returning `false` from `onBackClick()` delegates to the system back
dispatcher. While the notification-permission warning is pending, each host foreground refresh
checks `POST_NOTIFICATIONS` and continues notification pairing automatically once it is granted.
This check is independent of notification-settings navigation; the settings hook only opens the
destination. Hosts that need custom top-bar actions should supply their own `topBar`.

All static UI and notification text lives in `src/main/res/values/strings.xml` with the `priv_ui_` prefix so apps can override or localize it.
