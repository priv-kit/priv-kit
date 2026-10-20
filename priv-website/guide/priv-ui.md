---
description: Add the Compose authorization page and configure automatic recovery.
---

# Privilege UI {#privilege-ui}

`priv-ui` provides a Compose authorization page and can restart the server using the last successful method. Use `priv-core` to build your own interface.

## Public entry points {#public-entry-points}

- `PrivilegeScaffold` provides the embedded Compose page.
- `PrivilegeUiViewModel` is an open `AndroidViewModel` controller.
- `PrivilegeUiConfig` enables startup modes and external providers.
- `PrivilegeUiExternalStartProvider` integrates an app-owned external path.
- `PrivilegeUi.desiredEnabled` exposes the saved recovery setting as a read-only `StateFlow<Boolean>`.
- `PrivilegeUi.startSilently(...)` replays the last successful method while
  automatic recovery is enabled. `ignoreAutomaticRecoverySetting = true`
  explicitly bypasses that setting.
- `PrivilegeUi.prepareExternalShellScript(...)` synchronously writes the optional external script and returns its `File`.

## Permission troubleshooting action {#permission-solutions}

**View solutions** opens the localized [permission guide](./startup-tutorial#permission-restrictions); **View restricted permissions** shows the denied list.

Pass `onViewPermissionSolutions` to replace the link with your own navigation:

```kotlin
PrivilegeScaffold(
    viewModel = viewModel,
    onViewPermissionSolutions = {
        navController.navigate("permission-help")
    },
)
```

Your callback replaces the default website navigation.
`PrivilegePreviewScaffold` supports the same callback. Omitting it or passing `null` uses the default link.

## Share one configuration {#application-scoped-config}

Create external providers and `PrivilegeUiConfig` once at process scope, then
pass the same instance to the foreground ViewModel and the headless entry point:

```kotlin
val privilegeUiConfig by lazy {
    PrivilegeUiConfig(
        startupModes = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
        ),
        externalStartProviders = listOf(shizukuProvider),
    )
}

val serverInfo = PrivilegeUi.startSilently(
    config = privilegeUiConfig,
)
```

Use application-scoped state. Keep provider IDs stable across upgrades.

`startupModes` sets tab order and rejects duplicates. External providers use the listed `EXTERNAL` position or append it; without providers the tab is hidden.

## Fixed manual startup script {#manual-startup-script}

`manualShellScriptPath = null` displays the full native command. Set a path to use an external script. Other shell processes can modify that script before execution, so enable it only if acceptable for your app.

For Android user 0, the path is relative to `Android/data/<package>` and includes the filename. Use `priv.sh` to place it beside `files`:

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    manualShellScriptPath = "priv.sh",
)
```

The UI writes under `Context.getExternalFilesDir(null)?.parentFile` and displays the shorter `/sdcard` alias:

```shell
adb shell sh /sdcard/Android/data/<package>/priv.sh
```

Nonzero users, including work profiles, use the full native command because Shell may not be able to read their external storage.

The UI displays the script command immediately and prepares it once per ViewModel in the background. Matching content is reused; changes are written atomically. After an app update, reopen the UI or call `prepareExternalShellScript(...)` to refresh installation paths. A changed target is overwritten; changing the configured path leaves the old file in place.

Storage failures fall back to the native command. Use a relative path within the app's external directory, with nonempty segments excluding `.`, `..`, backslashes, and control characters. The shell must be able to read the script. For desktop-shell compatibility, use letters, digits, `/`, `.`, `_`, and `-`; other characters use POSIX quoting.

To prepare the script without opening the UI:

```kotlin
val scriptFile: File? = PrivilegeUi.prepareExternalShellScript("priv.sh")
val scriptPath: String? = scriptFile?.absolutePath
```

The API returns the actual absolute `File`. It returns `null` for a null path, nonzero user, unavailable storage, or file access failure; starter-resolution errors propagate. Run it on `Dispatchers.IO`: preparation is synchronous and may inspect APKs or sync a file. Prepare scripts and start the server as separate operations.

## Embed the scaffold {#embed-scaffold}

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    privilegeUiConfig,
) {
    override fun onBackClick(): Boolean {
        return true
    }
}

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

The scaffold owns its Activity Result launchers and returns permission results
to the same suspended ViewModel operation.

Starting while connected asks for restart confirmation. Cancel keeps the server; confirm stops it before replacement. Insufficient kill permission is reported in a Snackbar. Custom hosts collect `serverRestartConfirmation` and call `confirmServerRestart()` or `cancelServerRestart()` to resume the waiting operation.

## Observe the server state {#server-state}

`PrivilegeScaffold` already observes the runtime internally to render its
status. When other application features depend on the connection, observe the
process-wide `Privilege.serverState` instead of a UI-specific callback.

The flow is available without the UI. See [connection observation](./activation#connection-state).

Observe `PrivilegeUi.desiredEnabled` separately when a custom surface needs to
show whether the user still wants automatic recovery:

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

This read-only state persists across disconnections and failed replay. Server
connectivity remains in `Privilege.serverState`.

## ADB settings {#adb-ui}

`priv-core` owns pairing, discovery, authorization, TCP/IP, and startup.
`priv-ui` presents those operations and their user interactions through
`PrivilegeScaffold`.

Configure the UI-owned ADB flow through `PrivilegeUiConfig`:

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

### Wireless Debugging UI {#wireless-debugging-ui}

The ADB panel polls Wireless Debugging and pairing state only while that mode is
selected. Its foreground flow can:

- show the pairing dialog and submit the six-digit code through `priv-core`;
- accept the code through the optional notification pairing flow;
- request `ACCESS_LOCAL_NETWORK` when the platform requires it;
- start Wireless Debugging after the saved ADB key is paired.

The scaffold handles notification pairing. If notification input is unavailable, use the foreground dialog alongside Android Settings in split-screen.

Set `notificationPairingChannelId` (default `priv_ui_adb_pairing`) and `notificationPairingNotificationId` (default `201`) to avoid host conflicts. The ID must be `1..Int.MAX_VALUE - 1` and reserves two consecutive values. The dedicated channel is created on demand and is not deleted.

Managed Wireless Debugging remains a `priv-core` capability. `priv-ui` reads
its status and passes the selected policy to Core, which owns
`Settings.Global` changes.

### TCP/IP UI {#tcp-ip-ui}

`PrivilegeUiConfig.tcpPort` selects the static port.
`PrivilegeUiConfig.adbTcpPolicy` controls whether the UI disables TCP/IP,
prefers an existing static endpoint, or offers to create one after Wireless
Debugging is paired.

Before the foreground flow asks `priv-core` to issue `adb tcpip`, the built-in
scaffold shows a one-shot confirmation. Cancelling leaves ADB unchanged. A
custom surface that calls `PrivilegeUiViewModel.enableTcpMode()` or
`startStaticTcpAdb()` collects `staticTcpSwitchConfirmation`, shows its own
warning, then calls `confirmStaticTcpSwitch()` or `cancelStaticTcpSwitch()`.

The UI can request local ADB key authorization and continue the same suspended
foreground operation after the system result. Passive status polling does not
change ADB settings.

## Silent startup {#exact-replay}

After a matching foreground operation receives its initial Binder connection,
the UI stores one method identifier:

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

Silent startup uses the saved method. It returns `null` when history, authorization, or startup prerequisites are unavailable. Complete permission requests, pairing, and external authorization in the foreground.

Foreground and silent starts share a process-wide lock. Choose one startup process in multi-process apps. During silent startup, the UI disables conflicting actions. A root manager may still show its own prompt if its saved grant has expired.

A matching successful foreground launch enables automatic recovery. Initial
connections outside a UI-owned foreground operation leave it unchanged. A
confirmed stop or the built-in "Disable automatic recovery" action clears it;
disconnection, server death, and failed replay preserve it.
`PrivilegeUi.desiredEnabled` publishes this persisted intent independently of
the current server connection.
`startSilently(...)` respects automatic recovery by default.
`ignoreAutomaticRecoverySetting = true` explicitly replays regardless of that
setting.

## Add Shizuku {#shizuku}

`priv-ui` displays Shizuku through an application-owned
`PrivilegeUiStreamingExternalStartProvider`. Its implementation should:

- return Shizuku availability and permission state from `snapshot()`;
- request permission and resume with the final state from
  `requestAuthorization()`;
- bind the Shizuku UserService in `start()` and pass the supplied
  `commandLine` to `PrivilegeExternalStartup.runThroughBridge(...)`;
- keep the provider ID stable so exact silent replay can find it after an app
  upgrade.

Register the provider in `PrivilegeUiConfig.externalStartProviders`, as shown
above. The [external startup example](./activation#external) explains the
Shizuku UserService AIDL, privileged endpoint, and binding. The sample contains
a complete
[Privilege UI provider](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt).

## Multiplatform preview {#multiplatform-preview}

`PrivilegePreviewScaffold()` previews the page on Android, JVM, and WasmJS in the host's Material 3 theme. Use `PrivilegeScaffold` for real Android operations.

Startup, pairing, and dialogs simulate success; pairing accepts any six digits. Copy uses the host clipboard. Closing the host ends the session. Try the [playground](/playground/).

The manual command uses a random installation path for `priv.kit.sample`. `useLegacyPackaging = true` (default) shows the extracted library; `false` shows the APK's `linker64` command. Switching preserves the path and session.

## Host-controlled page {#host-controlled-page}

`PrivilegeScreen` renders host-provided `PrivilegeUiScreenState` and `PrivilegeUiActions` on Android, JVM, and WasmJS. The host must provide `showFeedback` and perform the operations.

```kotlin
PrivilegeScreen(
    state = screenState,
    actions = hostActions,
    showFeedback = ::showMessage,
    systemPrompt = prompt, // PrivilegeUiPromptState(title, message), or null
    topBar = { AppTopBar() },
)
```

Implement all action callbacks, state updates, and cancellation. Report unsupported actions and use the host clipboard. `requestBatteryOptimization` reports whether it opened a request or settings page. `interactionEnabled` controls display availability; `canInteract` checks permission at click time and should allow cancellation while busy. `busy` prevents conflicting starts.

Keep `startupModes` nonempty. A selected mode absent from that list falls back to its first entry.
Host-provided messages and `PrivilegeUiPromptState` text must already be localized. Increment
`connectionSerial` when replacing a connection to reset connection-specific presentation.
Lists are snapshots: publish a new state instead of mutating a list in place.

The host handles platform permission requests and supplies prompt text.

`systemPromptWindowInsets` controls prompt placement independently of `contentWindowInsets`.
It defaults to the top of `WindowInsets.safeDrawing`. Desktop simulation hosts can pass their
simulated status bar/cutout insets, for example `WindowInsets(top = 48.dp)`, without shifting
page content. Android callers can retain the default.

## User tutorial links {#startup-tutorial-links}

The ADB fingerprint-header link, Manual link beside Copy, pairing dialog guide, and permission solutions open
sections of one user guide. `PrivilegeScaffold`, `PrivilegePreviewScaffold`, and
`PrivilegeScreen` accept:

- `startupTutorialUrl`: a complete replacement guide URL. By default the current UI
  language selects the official English or Chinese guide. Existing fragments are replaced
  with the topic anchor; query parameters are preserved.
- `onViewStartupTutorial`: receives `PrivilegeUiTutorialTopic` and replaces default navigation.
- Existing `onViewPermissionSolutions`: overrides only permission help, taking precedence
  over the unified callback.

For example: `PrivilegeScaffold(startupTutorialUrl = "https://example.com/help/startup")`.
When a callback applies, only that callback runs. Guide links remain available while busy.

The guide is rendered by `guide/startup-tutorial/StartupGuide.vue` with localized content in `content.ts`. Self-host those files with their components and styles. Preserve anchors such as `adb`, `pairing`, `manual`, `tcp-ip`, and `permission-restrictions`.