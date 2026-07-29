---
description: Use the recommended Compose authorization surface and configure exact silent replay.
---

# Privilege UI {#privilege-ui}

`priv-ui` is the recommended starting point for Priv Kit integration. It
provides runtime authorization status, startup entry points, and exact replay
of the last successful foreground method through a Compose interface. The
module remains optional for applications building a custom interface directly
with `priv-core`.

## Public entry points {#public-entry-points}

- `PrivilegeScaffold` provides the embedded Compose page.
- `PrivilegeUiViewModel` is an open `AndroidViewModel` controller.
- `PrivilegeUiConfig` enables startup modes and external providers.
- `PrivilegeUiExternalStartProvider` integrates an app-owned external path.
- `PrivilegeUi.startSilently(...)` replays the last successful method while
  automatic recovery is enabled. Pass `ignoreAutomaticRecoverySetting = true`
  only when the application intentionally needs to ignore that setting.

## Keep one process-scoped configuration {#application-scoped-config}

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

The top-level property and its external providers must not retain an `Activity`.
Provider identifiers are persistent keys and should remain stable across app
upgrades.

`startupModes` is an ordered list that controls the authorization tab order,
and duplicate modes are rejected. With configured external providers,
`EXTERNAL` keeps its listed position or is appended when omitted. With no
external providers, the External tab is hidden even when `EXTERNAL` is listed.

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

When a server is already connected, pressing a built-in Root, ADB, or external
start button opens a restart confirmation. Cancelling leaves the current server
untouched. Continuing asks the selected starter identity to kill the old
process before starting its replacement. If that identity lacks permission to
kill the old process, the attempt stops and the scaffold reports the failure in
a Snackbar. Custom surfaces collect `serverRestartConfirmation`, render their
own confirmation UX, then call `confirmServerRestart()` or `cancelServerRestart()`.
The requesting start operation stays suspended until that decision returns, so
the selected workflow continues in its original coroutine.

## Observe the server state {#server-state}

`PrivilegeScaffold` already observes the runtime internally to render its
status. When other application features depend on the connection, observe the
process-wide `Privilege.serverState` instead of a UI-specific callback.

The state remains available whether or not `PrivilegeScaffold` is currently
composed. [Startup methods](./activation#connection-state) shows
both application-wide collection and screen-local rendering.

## ADB UI orchestration {#adb-ui}

`priv-ui` does not implement the ADB protocol or start the server by itself. It
uses the pairing, discovery, authorization, TCP/IP, and startup APIs from
`priv-core`, then presents their state and required user interactions through
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

The notification pairing service is internal to `priv-ui`. Hosts embed
`PrivilegeScaffold`; they do not start `PrivilegeAdbPairingService` directly.
When notification input is unavailable, the scaffold keeps the foreground
pairing dialog available for a split-screen flow with Android Settings. Its
warning returns continue-without-notifications, granted-in-settings, or cancel
to the same suspended pairing operation.

Managed Wireless Debugging remains a `priv-core` capability. `priv-ui` reads
its status and passes the selected policy to `priv-core`; it does not write
`Settings.Global` itself.

### TCP/IP UI {#tcp-ip-ui}

`PrivilegeUiConfig.tcpPort` selects the static port.
`PrivilegeUiConfig.adbTcpPolicy` controls whether the UI disables TCP/IP,
prefers an existing static endpoint, or offers to create one after Wireless
Debugging is paired.

Before the foreground flow asks `priv-core` to issue `adb tcpip`, the built-in
scaffold shows a one-shot confirmation. Cancelling leaves ADB unchanged. A
custom surface that calls `PrivilegeUiViewModel.enableTcpMode()` or
`startStaticTcpAdb()` must collect `staticTcpSwitchConfirmation`, show its own
warning, then call `confirmStaticTcpSwitch()` or `cancelStaticTcpSwitch()`.

The UI can request local ADB key authorization and continue the same suspended
foreground operation after the system result. Passive status polling does not
change ADB settings.

Silent replay does not show any of these interactions. It does not pair, request
permissions, request TCP authorization, or create a static port. It returns
`null` when the saved ADB method is not already ready.

## Understand exact replay {#exact-replay}

After a matching foreground operation receives its initial Binder connection,
the UI stores one method identifier:

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

Silent replay is a headless, method-exact operation. It uses the saved method
and returns `null` when history, authorization, or startup prerequisites are
unavailable, leaving permission prompts, pairing, and external authorization to
the foreground flow.

Foreground and silent attempts share a process-local start gate. Multi-process
apps must designate one process to initialize and invoke Priv Kit startup.
Accepted foreground effects retain their interactive lease until completion.
While a silent attempt owns the gate, the built-in UI disables side-effecting
entries and reconciles runtime state before enabling them again. A root manager
may still show its own authorization UI when a remembered grant is no longer
valid.

An accepted initial launch enables automatic recovery. Only a confirmed stop or
the built-in "Disable automatic recovery" action disables it; disconnection,
server death, and failed replay leave it unchanged.
`startSilently(...)` respects automatic recovery by default. Pass
`ignoreAutomaticRecoverySetting = true` only when the application intentionally
needs to replay regardless of that setting.

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
