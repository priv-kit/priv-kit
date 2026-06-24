# priv-runtime

Client-side runtime orchestration module for Priv Kit.

Namespace and package root: `priv.kit.runtime`.

Common entry points:

- `PrivilegeRuntime.startRoot()` for the minimal Root runtime loop.
- `PrivilegeRuntime.startAdb()` for Wireless Debugging / TCP ADB startup, including custom `PrivilegeAdbIdentity`.
- Process-wide current Privileged Server Binder state, exposed through `PrivilegeRuntime` global methods.
- UserService entry points for app-defined Binder services: start, bind, stop, and status.

Advanced entry points:

- `PrivilegeRuntime.createManualShellCommand()` for generating a token-hidden starter command that a developer can paste into `adb shell`.
- `PrivilegeRuntime.prepareManualShell()` for callers that still want a command plus a blocking pending-handshake wait.
- `PrivilegeRuntime.createExternalStartCommand()` for generating a non-blocking command that an external authorization tool can execute.
- `PrivilegeRuntime.prepareExternalStart()` for callers that want an external command plus a pending-handshake wait.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by the persisted owner token. Manual token-hidden starter commands can resolve that token through the provider before the final Binder handoff.
- Ready-server connection helpers, owner-death reconnect configuration, and raw Binder bridge types for custom diagnostics or low-level Binder validation.

Runtime owns token generation, shared server launch command construction, pending handshakes, protocol validation, current server Binder installation, and Binder death handling. Startup strategy modules only execute or transport the launch command.

`PrivilegeHandshakeProvider` initializes the runtime with the app `Context`, so callers use `PrivilegeRuntime` directly without passing `Context` into start, ADB, manual shell, or ready-server APIs.

The runtime module carries `priv-server` as a runtime-only dependency so apps do not need to declare the server module separately. The server module contributes the R8 consumer rule that keeps its `app_process` entry point.

`startRoot()`, `startAdb()`, `createManualShellCommand()`, `prepareManualShell()`, `createExternalStartCommand()`, and `prepareExternalStart()` accept `followDeathDelayMillis`. When the app-side owner process dies, the Privileged Server waits for that grace period before exiting. The default is `PrivilegeRuntime.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS` (10 minutes). Use `0` to exit immediately.

By default, owner-death reconnect is passive: the server waits until the app main process is already running again, then sends the Binder handoff. Set `activeReconnectOnOwnerDeath = true` only if the server should actively call the app handshake provider while the app process is dead, which may start the app process.

The latest owner-death configuration is passed in the launch command and kept by the Privileged Server in memory. Calling `configureOwnerDeathBehavior()` while connected also pushes the new values to the current server immediately, so the next owner-process death follows the latest app-side configuration.

Like shizuku-api, the runtime treats the Privileged Server Binder as a single process-wide handle. A repeated handshake for the same Binder keeps the current global server state; a handshake for a replacement Binder installs the new server state.

`PrivilegeRuntime.getServerInfo()`, `PrivilegeRuntime.requireBinderEndpoint()`, `PrivilegeRemoteBinderWrapper`, and `PrivilegeRemoteSystemServiceBinder` all resolve the server Binder through the same global getter. If the server was killed after a caller cached a framework service proxy backed by `PrivilegeRemoteBinderWrapper` or the raw system-service Binder bridge, the next transaction is normalized to `PrivilegeServerDisconnectedException` instead of leaking raw Binder state.

If a reconnected server reports a different protocol, server version, or APK classpath identity than the current app runtime, the runtime rejects it and returns a replacement `app_process` command built from the current APK. The server executes that command in-place before exiting, so client and server code come from the same install without repeating the original privilege authorization flow.

Manual Shell only creates a command for the same Binder handoff path. It does not execute `adb`, implement Wireless Debugging, or add an ADB startup strategy.

External Start Command uses the same Binder handoff path. `priv-runtime` builds a detached `app_process` launch command and can wait for the handshake; app-provided Shizuku/Dhizuku/UserService code only executes that command and returns.

This module does not expose typed Android system service wrappers, UI, Compose, package/input/settings/app-ops/activity APIs, or app-defined UserService business methods. UserService bind returns a raw Binder for the app's own AIDL Stub, and system-service access is limited to an explicit-name raw Binder transaction bridge.
