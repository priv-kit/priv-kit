# priv-runtime

Client-side runtime orchestration module for Priv Kit.

Namespace and package root: `priv.kit.runtime`.

Common entry points:

- `PrivilegeRuntime.startRoot()` for the minimal Root runtime loop.
- `PrivilegeRuntime.startAdb()` for Wireless Debugging / TCP ADB startup, including custom `PrivilegeAdbIdentity`.
- Process-wide current Privileged Server Binder state, exposed through `PrivilegeRuntime` global methods.
- UserService entry points for app-defined Binder services: start, bind, stop, and status.

Advanced entry points:

- `PrivilegeRuntime.createShellStartCommand()` for generating the short native starter command that a developer can show to a user or pass to an external authorization tool.
- `PrivilegeRuntime.prepareShellStart()` for callers that want a command plus a blocking pending-handshake wait.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by `android.permission.INTERACT_ACROSS_USERS_FULL` and the persisted owner token. Short native starter commands complete one provider handoff that returns the owner token and current in-memory owner-death config.
- Ready-server connection helpers, owner-death reconnect configuration, and raw Binder bridge types for custom diagnostics or low-level Binder validation.

Runtime owns token generation, shared server launch command construction, the native starter executable, Root `su` execution, pending handshakes, protocol validation, current server Binder installation, and Binder death handling. The ADB module only executes or transports the launch command.

`PrivilegeHandshakeProvider` initializes the runtime with the app `Context`, so callers use `PrivilegeRuntime` directly without passing `Context` into start, ADB, shell-start, or ready-server APIs. The provider remains exported so shell/root/external privileged starters can reach it, but normal apps are stopped by the provider permission before the owner-token handshake runs.

The runtime module carries `priv-server` as a runtime-only dependency so apps do not need to declare the server module separately. The server module contributes the R8 consumer rule that keeps its `app_process` entry point.

`startRoot()`, `startAdb()`, `createShellStartCommand()`, and `prepareShellStart()` accept `followDeathDelayMillis`. When the app-side owner process dies, the Privileged Server waits for that grace period before exiting. The default is `PrivilegeRuntime.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS` (10 minutes). Use `0` to exit immediately.

By default, owner-death reconnect is passive: the server waits until the app main process is already running again, then sends the Binder handoff. Set `activeReconnectOnOwnerDeath = true` only if the server should actively call the app handshake provider while the app process is dead, which may start the app process.

The latest owner-death configuration is held in the runtime process and returned by the app-side provider during the initial server handoff. A running server keeps the configuration it received at startup; later calls to `configureOwnerDeathBehavior()` affect the next server start only.

Like shizuku-api, the runtime treats the Privileged Server Binder as a single process-wide handle. A repeated handshake for the same Binder keeps the current global server state; a handshake for a replacement Binder installs the new server state.

`PrivilegeRuntime.getServerInfo()`, `PrivilegeRuntime.requireBinderEndpoint()`, `PrivilegeRemoteBinderWrapper`, and `PrivilegeRemoteSystemServiceBinder` all resolve the server Binder through the same global getter. If the server was killed after a caller cached a framework service proxy backed by `PrivilegeRemoteBinderWrapper` or the raw system-service Binder bridge, the next transaction is normalized to `PrivilegeServerDisconnectedException` instead of leaking raw Binder state.

If a server reports a different protocol or APK classpath identity than the current app runtime, the runtime rejects that handshake. Startup paths are expected to launch code from the current install.

Shell Start only creates a command for the same Binder handoff path. It does not execute `adb`, implement Wireless Debugging, or add an ADB startup strategy. UI modules or host apps may add an `adb shell` prefix when they want to display a host-side command.

External authorization tools use the same Shell Start Command. App-provided Shizuku/Dhizuku/UserService code only executes that command and returns while the app waits for the handshake.

This module does not expose typed Android system service wrappers, UI, Compose, package/input/settings/app-ops/activity APIs, or app-defined UserService business methods. UserService bind returns a raw Binder for the app's own AIDL Stub, and system-service access is limited to an explicit-name raw Binder transaction bridge.
