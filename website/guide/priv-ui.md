---
description: Embed the optional Compose authorization surface and configure exact silent replay.
---

# Privilege UI

`priv-ui` is an optional Compose module for runtime authorization status,
activation entry points, and exact replay of the last successful foreground
method.

## Public entry points

- `PrivilegeScaffold` provides the embedded Compose page.
- `PrivilegeUiViewModel` is an open `AndroidViewModel` controller.
- `PrivilegeUiConfig` enables startup modes and external providers.
- `PrivilegeUiExternalStartProvider` integrates an app-owned external path.
- `PrivilegeUi.startSilentlyIfEnabled(...)` replays only when the desired-state
  latch is enabled.
- `PrivilegeUi.startSilently(...)` performs the same exact replay regardless of
  that latch.

The scaffold consumes the host application's Material 3 theme. Wrap it in the
app's own theme for branded light, dark, or dynamic colors.

## Keep one application-scoped configuration

Create external providers and `PrivilegeUiConfig` once, then pass the same
instance to the foreground ViewModel and the headless entry point:

```kotlin
class App : Application() {
    val privilegeUiConfig by lazy {
        PrivilegeUiConfig(
            externalStartProviders = listOf(myShizukuProvider),
        )
    }
}

val serverInfo = PrivilegeUi.startSilentlyIfEnabled(
    context = app,
    config = app.privilegeUiConfig,
)
```

Keep external providers application-scoped and Activity-free. Their identifiers
are persistent keys and should remain stable across app upgrades.

## Embed the scaffold

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    (application as App).privilegeUiConfig,
) {
    override fun onBackClick(): Boolean {
        return true
    }

    override fun onConnected(serverInfo: PrivilegeServerInfo) {
        // Update host state after a new connection.
    }
}

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

The scaffold owns its Activity Result launchers and returns permission results
to the same suspended ViewModel operation.

## Understand exact replay

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

## Desired state

The desired-state latch is stored as one byte, `1` or `0`, in
`filesDir/.priv-kit/ui-desired-enabled`. Every accepted `INITIAL_LAUNCH`
connection writes `1`, including a server started from a copied external shell
command. `OWNER_RECONNECT`, disconnection, process death, and failed replay
preserve the stored value. A confirmed stop or the built-in disconnected-state
"Disable automatic recovery" action writes `0`.

The latch tracks user intent separately from current server liveness. A launch
outside a matching UI operation can enable it while replay history remains
unchanged. Silent recovery starts when a UI-confirmed method is available;
otherwise, `startSilentlyIfEnabled(...)` returns `null` and the built-in warning
remains visible.

The last UI-confirmed method is stored in
`filesDir/.priv-kit/ui-start-method`. `startSilentlyIfEnabled(...)` checks the
desired-state byte first, then applies the same exact replay behavior as
`startSilently(...)`.

## Host-owned integrations

The host app supplies Shizuku binding and any package, input, settings, app-ops,
or logging tools it needs. Privilege UI stays focused on runtime status,
activation, and recovery.
