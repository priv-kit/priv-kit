# priv-ui

Optional UI and UI-originated startup helper module for Priv Kit.

Namespace and package root: `priv.kit.ui`.

This module provides a reusable embedded page for runtime authorization status and authorization entry points, plus a headless entry point that can replay the last successful foreground start.

Public entry points:

- `PrivilegeScaffold`, the root Compose page.
- `PrivilegeUiViewModel`, an `open` `AndroidViewModel` controller that callers may subclass.
- `PrivilegeUiConfig`, used to enable startup modes, polling intervals, and external start providers.
- `PrivilegeUiExternalStartProvider`, whose suspend authorization and startup methods keep the
  requesting ViewModel coroutine continuous across third-party prompts and callbacks.
- `PrivilegeUi.startSilentlyIfEnabled(...)`, the desired-state-gated headless recovery entry point.
- `PrivilegeUi.startSilently(...)`, the lower-level exact replay entry point that ignores the desired-state gate.

Process-wide owner-death behavior remains a runtime concern. Configure it through
`PrivilegeConfig` before starting a server; `PrivilegeUiConfig` does not mirror or
override that global runtime state.

Compose Foundation and Material 3 are API dependencies because `PrivilegeScaffold`
exposes Material 3 Scaffold slots and parameters. The lifecycle Compose adapter remains
an implementation dependency; host apps using `viewModel()` as shown below should
declare their own `androidx.lifecycle:lifecycle-viewmodel-compose` dependency.

`PrivilegeScaffold` consumes the caller's Compose `MaterialTheme` colors. Apps that need light, dark, dynamic, or branded authorization UI should wrap it in their own Material 3 theme instead of configuring colors through `PrivilegeUiConfig`.

Status observation is owned internally by the ViewModel. A StateFlow-driven effect follows the selected
startup mode, runs only the relevant ADB or external-provider polling coroutine, and cancels the
previous mode automatically. Hosts use `PrivilegeScaffold`; they do not consume its render state
or create and close polling handles.

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
- A connected-server warning above the authorization method tabs when the privileged service is subject to permission restrictions. The status is checked after each connection and whenever the host returns to the foreground; Root servers skip the permission check.

For manual shell startup, the UI reads `Privilege.nativeStarterPath` and shows the direct
`adb shell <native-starter-path>` command. The native starter remains inside the installed
application, and the UI only prepares display and clipboard text.

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

Before any foreground UI path issues `adb tcpip` to create or restart a static endpoint, the built-in UI requires a one-shot confirmation that explains the ADB restart and its impact on other ADB-backed processes. Cancelling the dialog leaves ADB unchanged. A configured endpoint that can be recovered through `ADB_ENABLED=1` does not show this confirmation, and foreground fallback never enables a static endpoint without confirmation.

Custom surfaces that call `PrivilegeUiViewModel.startStaticTcpAdb()` or `enableTcpMode()` must collect `staticTcpSwitchConfirmation`. When it becomes non-null, they should present the same restart warning and call `confirmStaticTcpSwitch()` or `cancelStaticTcpSwitch()`. The pending value identifies whether confirmation will continue service startup or only enable the port; observing it never performs the switch. The requesting ViewModel-owned coroutine remains suspended while the dialog is visible, so confirmation resumes the same static-TCP workflow instead of constructing a second startup attempt.

The foreground service action calls `PrivilegeUiViewModel.startInteractive()` and tries configured workflows in the order supplied by the UI before reporting a generic failure. If a Root or ADB command may have created a detached server but its Binder handshake does not complete, fallback stops and reports the generic failure because that server may still arrive later. An External request remains in the same start session while the runner waits for its Binder connection or timeout; a timeout also stops fallback because the requested server may still arrive later. Every foreground start uses the same cooperative cancellation model: the first cancellation request changes the owning controls from Cancel to a disabled Cancelling state, Root and ADB observe cancellation at their internal checkpoints, and an External provider call with no checkpoints remains in Cancelling until that call returns. Other startup controls stay disabled while a start is running or cancelling.

## Foreground, silent, and owner-reconnect startup

When a foreground start owned by `PrivilegeUiViewModel` commits a launch method and receives the matching `INITIAL_LAUNCH` Binder connection for that runtime operation before cancellation, `priv-ui` records the exact winning method in `filesDir/.priv-kit/ui-start-method`. The file contains one raw UTF-8 method ID, not JSON or a preference schema:

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

Silent starts, retained-server `OWNER_RECONNECT` handshakes, already-connected servers, manual shell starts outside the matching foreground operation, cancelled starts, and failed starts do not replace this method value.

Separately, `priv-ui` owns a desired-state latch in `filesDir/.priv-kit/ui-desired-enabled`. Its entire content is exactly one ASCII byte: `1` for enabled or `0` for disabled. A missing or invalid file is disabled. The library's non-exported initialization provider installs the connection listener after core runtime initialization but before app providers are published, so every accepted `INITIAL_LAUNCH` connection writes `1`, including a server started from a copied external shell command while the app process is cold. An `OWNER_RECONNECT`, server death, disconnect, or failed recovery attempt leaves the value unchanged.

An accepted launch outside a matching UI-owned foreground operation enables this latch but does not invent or replace a replay method. If no UI-confirmed method history exists and that server later stops, gated recovery returns `null` and the disconnected warning remains visible until the user disables automatic recovery or starts a server again.

There is no general-purpose switch for this latch. A confirmed stop action in the built-in UI writes `0` before asking the server to shut down. When the latch is `1` but runtime state is disconnected or failed, the top of `PrivilegeScaffold` shows a warning card whose "Disable automatic recovery" action also writes `0`. This keeps the value about user intent instead of current server liveness.

`PrivilegeUi.startSilentlyIfEnabled(context, config)` returns `null` immediately when the desired-state latch is disabled. When enabled, it delegates to the same exact replay behavior as `startSilently(context, config)`. The lower-level entry point remains available for callers that intentionally want to replay regardless of the latch.

After obtaining the process-local start gate, `PrivilegeUi.startSilently(context, config)` first returns an already-connected or ready server, if present. During the runtime's app-start reconciliation window it then gives a retained server time to complete owner reconnect before reading the saved method and committing a new launch. If no connection wins, it attempts only that exact method. It does not initialize Compose, create a ViewModel, require an `Activity`, fall back to another method, show a snackbar, invoke Android permission launchers, or update the saved method. Without an existing connection, missing history, an unknown or disabled method, missing authorization, startup failure, and timeout all return `null`.

Foreground and silent startup are mutually exclusive through one process-local gate. Accepted foreground startup effects, including runtime start/stop, TCP changes, pairing, permission requests, and external authorization calls, retain nestable leases scoped to one foreground ViewModel owner until their owned work completes. A different ViewModel cannot join that owner's nesting, and `startSilently(...)` returns `null` while any foreground lease remains. The two UI entry points use first-acquired ownership without queuing or preemption.

Owner reconnect participates through the runtime arbiter rather than the UI gate. An already-connected/ready server wins first; a retained server whose reconnect arrives during preflight wins before Root, ADB, or external launch side effects are committed. After a foreground or silent start commits its runtime lease, a late `OWNER_RECONNECT` is rejected and the new launch continues. Permission requests are additionally scoped to their active `PrivilegeScaffold` host and are discarded when the last host leaves, so a detached launcher cannot keep the gate occupied or replay a stale prompt. While silent startup owns the gate, the built-in UI disables new side-effecting entries. After silent startup releases the gate, each existing ViewModel re-reads runtime state before enabling those entries again, so a just-connected server cannot race with another foreground start. A multi-process app must initialize and invoke Priv Kit startup from only one designated app process; calling `startSilently(...)` during every process's `Application` initialization can start duplicate attempts.

Silent method behavior is deliberately narrow:

- Wireless ADB may temporarily enable Wireless Debugging when `enableManagedWirelessAdb` is enabled and the app already has the runtime capability required to manage it. It never starts pairing, and returns `null` when the saved ADB identity is not paired. If the platform requires `ACCESS_LOCAL_NETWORK` and it has not already been granted, the method returns `null` without requesting it.
- Static TCP uses only the current `config.tcpPort`, with discovery and Wireless Debugging fallback disabled. An unavailable listener or unauthorized ADB key returns `null`; the authorization request flow is not invoked.
- External startup resolves the exact saved provider ID from the supplied config, requires its current `snapshot()` to report `canStart`, and never calls `requestAuthorization()`. Provider IDs are persistent keys and should remain stable across app upgrades. The suspend snapshot and start calls share `startTimeoutMillis` and should cooperate with coroutine cancellation.
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
val serverInfo = PrivilegeUi.startSilentlyIfEnabled(
    context = app,
    config = app.privilegeUiConfig,
)
```

An `object` or top-level lazy property is also suitable when it obtains only application-scoped dependencies. External providers must not retain an `Activity`, and `snapshot()` should remain a read-only suspend status check. `requestAuthorization(...)` may present the third-party prompt and returns the final post-authorization snapshot; callback APIs can bridge with `suspendCancellableCoroutine` and must unregister listeners on cancellation. The UI then continues startup directly in the same ViewModel coroutine, without a foreground-resume reconciliation path.

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
permissions and returns each result to the ViewModel. The corresponding ViewModel-owned
operation stays suspended until that result arrives: notification pairing continues from its
permission branch, while an ADB start granted local-network access retries once in the same
startup workflow. Removing the final Scaffold host or clearing the ViewModel cancels the pending
request and releases its interaction lease. Host subclasses may
override `onBackClick()`, `onConnected(...)`, and
`onNotificationPermissionSettingsRequested(...)`. These hooks should update host state,
emit host events, or customize notification-settings navigation; a ViewModel must not retain
the settings hook's `Context`, an `Activity`, `NavController`, Compose state holder, or
Activity Result launcher. Returning `false` from `onBackClick()` delegates to the system back
dispatcher. While the notification-permission warning is pending, each host foreground refresh
checks `POST_NOTIFICATIONS` and continues notification pairing automatically once it is granted.
This check is independent of notification-settings navigation; the settings hook only opens the
destination. Hosts that need custom top-bar actions should supply their own `topBar`.

All static UI and notification text lives in `src/main/res/values/strings.xml` with the `priv_ui_` prefix so apps can override or localize it. Internally, live state and one-shot UI feedback keep resource references and formatting arguments until the text reaches Compose, a notification, or another presentation boundary. This lets retained ViewModels immediately render a newly selected application locale instead of holding strings resolved under the previous configuration. External provider messages, diagnostics, and existing startup log lines remain materialized text and are not retroactively translated.
