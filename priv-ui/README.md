# priv-ui

`priv-ui` is the optional Compose Multiplatform authorization page and Android recovery module. Its public package root is
`priv.kit.ui`.

## Main APIs

- `PrivilegeScaffold` is the Android authorization page with real runtime integration.
- `PrivilegePreviewScaffold` displays the same page on Android, JVM, and WasmJS with in-memory simulated startup and dialogs.
- `PrivilegeUiViewModel` is an open `AndroidViewModel` for custom hosts.
- `PrivilegeUiConfig` configures startup modes, polling, notification pairing, and external
  providers.
- `PrivilegeUiExternalStartProvider` connects an app-owned authorization bridge.
- `PrivilegeUi.desiredEnabled` exposes automatic-recovery intent as a read-only process-wide flow.
- `PrivilegeUi.startSilently(...)` replays the last successful foreground method.

Owner-death behavior belongs to Core and is configured through `PrivilegeConfig`. Updates are
pushed to a connected server and apply to the next owner death.

## Compose integration

`PrivilegeScaffold` uses the caller's Material 3 theme and exposes the nested Scaffold slots,
colors, and insets. Compose Foundation and Material 3 are API dependencies; apps using
`viewModel()` declare `androidx.lifecycle:lifecycle-viewmodel-compose` themselves.

Create external providers and `PrivilegeUiConfig` once per process, then share the instance between
the foreground ViewModel and silent startup. These process-scoped objects hold application data,
not an `Activity`. Provider IDs are persistent keys and stay stable across upgrades.

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

`startupModes` controls tab order. External providers keep the listed `EXTERNAL` position or append
the tab when it is omitted. With no providers, the tab stays hidden.

The scaffold owns Activity Result launchers and returns results to the original suspended
ViewModel operation. Removing the last host or clearing the ViewModel cancels pending permission
work and releases its interaction lease.

## Authorization surface

The built-in page covers Root, manual shell, Wireless ADB pairing, static TCP, external providers,
startup transcripts, connection status, and permission-restriction warnings. It also shows a
restart confirmation when a server is connected. Confirmation resumes the original start
coroutine; cancellation leaves the current server running.

Battery guidance opens Android's direct exemption confirmation when the merged manifest contains
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Hosts can remove that permission with manifest merge, in
which case the page opens Android's optimization list and app details instead.

The scaffold observes Core state internally. Features outside the page collect
`Privilege.serverState`; custom automatic-recovery surfaces collect `PrivilegeUi.desiredEnabled`.

## ADB UI

Core owns pairing, discovery, authorization, TCP control, and startup. `priv-ui` selects those
operations and presents their state and user interactions.

The ADB panel polls while selected and keeps the latest completed snapshot visible during refresh.
Notification pairing is implemented by the internal `PrivilegeAdbPairingService`; the foreground
dialog remains available when notification input cannot be used.

`notificationPairingChannelId` defaults to `priv_ui_adb_pairing` and stays dedicated to this flow.
`notificationPairingNotificationId` defaults to `201` and reserves that ID plus the next one. Valid
values are `1..Int.MAX_VALUE - 1`, chosen outside the host's other notification IDs.

Managed Wireless Debugging status comes from Core. The UI passes policy and startup options while
Core performs any permitted `Settings.Global` changes.

Static-TCP creation and restart show a one-shot warning before `adb tcpip` dispatch. Custom surfaces
collect `staticTcpSwitchConfirmation`, render the warning, and return the decision through
`confirmStaticTcpSwitch()` or `cancelStaticTcpSwitch()`. Passive polling remains read-only.

## Foreground and silent startup

A successful UI-owned foreground launch stores one exact method ID:

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

The same completion enables automatic recovery. Disconnection, server death, owner reconnect, and
failed replay keep that user choice unchanged. A confirmed Stop action or the built-in Disable
automatic recovery action clears it.

`PrivilegeUi.startSilently(config)` first accepts an already-connected or reconnecting server, then
replays the saved method. It returns `null` when the method or its existing authorization is
unavailable. Silent replay is headless: pairing, permission prompts, TCP creation, and external
authorization remain foreground interactions. `ignoreAutomaticRecoverySetting = true` explicitly
replays regardless of the saved user choice.

Foreground and silent starts share one process-local gate. A retained owner reconnect can win
during preflight; after a new start commits, that start owns the result. Multi-process apps choose
one process for Priv Kit initialization and startup.

## External providers

An external provider reports status through `snapshot()`, performs authorization through
`requestAuthorization()`, and runs the supplied command in `start()`. Suspend callbacks cooperate
with cancellation and unregister listeners when cancelled.

Shizuku integration lives in the app as a `PrivilegeUiStreamingExternalStartProvider`. Its
UserService executes the supplied native starter through `PrivilegeExternalStartupHost`, while the
main process bridges pipes and completion with `PrivilegeExternalStartup.runThroughBridge(...)`.

## Text and localization

Static UI and notification text shares `src/commonMain/composeResources/values/strings.xml` with the `priv_ui_
prefix. Resource references stay unresolved until presentation so retained ViewModels follow the
current application locale. External-provider messages and startup logs remain materialized text.

## Multiplatform boundaries

`commonMain` owns components, presentation models, callbacks, and English/Simplified Chinese resources.
`androidMain` owns the existing ViewModel, Core integration, permission hosts, system prompts,
notifications, and recovery. Only the Android variant depends on Core and Shared. Runtime objects
are mapped to presentation values at the Android composition boundary; retained resource text is
resolved against the current Android locale. Notification strings use Android IDs generated from
the same XML source as Compose resources. Notification layouts remain Android-only.

Wrap `PrivilegePreviewScaffold()` in the host's Material 3 theme. This entry point creates no
ViewModel, Core runtime, polling job, permission request, or external provider. Its session-local
simulation drives the shared page's normal states and actions. Root, Wireless ADB,
static TCP, and external authorization default to success after a short cancellable delay. Any six
digits complete pairing. Existing restart, stop, pairing, and TCP confirmation dialogs remain usable.
The manual tab supplies a command with a randomly generated installation path for `priv.kit.sample`;
the top Start service action simulates its execution. `useLegacyPackaging` (default `true`) selects
the extracted library or in-APK linker command without resetting the simulated session.
Copy actions use the host clipboard, but commands are never executed. Host disposal cancels pending work.
The simulation shares presentation models and components with Android, not Android runtime operations.

`:priv-playground` is the unpublished Desktop/Wasm host. `priv-website` embeds its Wasm output.
Run `./gradlew :priv-ui:jvmTest :priv-ui:testAndroidHostTest` for shared presentation tests and
the existing Android regression suite.
