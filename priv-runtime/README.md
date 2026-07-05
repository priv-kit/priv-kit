# priv-runtime

Client-side runtime orchestration module for Priv Kit.

Namespace and public package root: `priv.kit`.

Common entry points:

- `Privilege.startRoot()` for the minimal Root runtime loop.
- `Privilege.startAdb()` for Wireless Debugging / TCP ADB startup, configured with `PrivilegeAdbStartOptions`.
- `Privilege.createAdbStarter()` for Wireless ADB pairing, TCP mode, and identity diagnostics backed by `PrivilegeAdbStarter`.
- Process-wide current Privileged Server Binder state, exposed through `Privilege` global methods.
- UserService entry points for app-defined Binder services: start, bind, and stop.

Advanced entry points:

- `Privilege.createShellStartCommand()` for generating the short native starter command that a developer can show to a user or pass to an external authorization tool.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by `android.permission.INTERACT_ACROSS_USERS_FULL` and the persisted owner token. Short native starter commands complete one provider handoff that returns the owner token and current in-memory owner-death config.
- Ready-server connection helpers, owner-death reconnect configuration, and raw Binder bridge types for custom diagnostics or low-level Binder validation.

Runtime owns token generation, shared server launch command construction, the native starter executable, Root `su` execution, ADB pairing/connect/startup, pending handshakes, protocol validation, current server Binder installation, Privileged Server entry points, and Binder death handling. Public ADB configuration and pairing types live in `priv.kit.adb`; raw startup transport SPI and wire protocol live under `priv.kit.internal.*`.

ADB startup supports managed Wireless Debugging as an internal strategy. When `PrivilegeAdbStartOptions.wirelessDebuggingControl` allows it, the merged app manifest still declares `WRITE_SECURE_SETTINGS`, and the app holds that permission, the runtime enables `adb_wifi_enabled`, discovers the dynamic `_adb-tls-connect._tcp` port, starts the server, and disables Wireless Debugging after the start attempt by default. After a Privileged Server connects, the runtime attempts to grant `WRITE_SECURE_SETTINGS` to the owner package when the permission is still declared and the server is root or has `android.permission.GRANT_RUNTIME_PERMISSIONS`, so later starts can use the managed Wireless Debugging path. If the permission declaration was removed with manifest merge or the permission is not granted and the mode is `IF_AVAILABLE`, startup continues with the existing manual Wireless Debugging / TCP behavior.

`Privilege.checkPermission(permName, pkgName, userId = cached current user id)` and `Privilege.grantRuntimePermission(packageName, permissionName, userId = cached current user id)` are thin pass-through calls to server-side `IPackageManager`. They do not add policy, discovery, batching, permission groups, app-ops, install flows, or package management abstractions.

`PrivilegeHandshakeProvider` initializes the runtime with the app `Context`, so callers use `Privilege` directly without passing `Context` into start, ADB, shell-start, or ready-server APIs. The provider remains exported so shell/root/external privileged starters can reach it, but normal apps are stopped by the provider permission before the owner-token handshake runs.

The runtime module carries its own `app_process` server and UserService entry points, and contributes the R8 consumer rule that keeps those `main(String[])` methods.

Configure owner-death behavior through the process-wide `PrivilegeConfig` object. When the app-side owner process dies, the Privileged Server waits for `followDeathDelayMillis` before exiting. The default is 10 minutes. Use `0` to exit immediately.

By default, owner-death reconnect is passive: the server waits until the app main process is already running again, then sends the Binder handoff. Set `PrivilegeConfig.activeReconnectOnOwnerDeath = true` only if the server should actively call the app handshake provider while the app process is dead, which may start the app process.

The latest owner-death configuration is held in the runtime process and returned by the app-side provider during the initial server handoff. A running server keeps the configuration it received at startup; later `PrivilegeConfig` changes affect the next server start only.

Like shizuku-api, the runtime treats the Privileged Server Binder as a single process-wide handle. A repeated handshake for the same Binder keeps the current global server state; a handshake for a replacement Binder installs the new server state.

`Privilege.getServerInfo()` and `PrivilegeBinderWrapper` both resolve the server Binder through the same global getter. If the server was killed after a caller cached a framework service proxy backed by `PrivilegeBinderWrapper`, the next transaction is normalized to `PrivilegeServerDisconnectedException` instead of leaking raw Binder state.

If a server reports a different protocol or APK classpath identity than the current app runtime, the runtime rejects that Binder handoff. When the caller is a trusted existing server, the app returns the current native starter command so the stale server can replace itself from the current install.

Shell Start only creates a command for the same Binder handoff path. The command can be executed at any time; the app observes the eventual Binder handoff through `addServerConnectedListener()` or `connectReadyServer()`. It does not execute `adb`, implement Wireless Debugging, or add an ADB startup strategy. UI modules or host apps may add an `adb shell` prefix when they want to display a host-side command.

External privileged hosts use the same Shell Start Command. App-provided Shizuku UserService or similar code-executing hosts can call `PrivilegeExternalStartup.runInCurrentProcess(...)` and return source/message log pairs to a main-process `PrivilegeExternalStartup` receiver; the runtime-level ready-server watcher owns connection state.

This module does not expose typed Android system service wrappers, UI, Compose, package/input/settings/app-ops/activity facades, or app-defined UserService business methods. UserService bind returns a raw Binder for the app's own AIDL Stub, and system-service access is limited to explicit-name raw Binder transaction bridges plus a small number of explicit framework pass-through calls.
