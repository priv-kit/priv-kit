# priv-core

Client-side runtime orchestration module for Priv Kit.

Namespace and public package root: `priv.kit.core`.

Common entry points:

- `Privilege.startRoot()` for the minimal Root runtime loop.
- `Privilege.startAdb()` for Wireless Debugging / TCP ADB startup, configured with `PrivilegeAdbStartOptions`.
- `Privilege.createAdbManager()` for Wireless ADB pairing, TCP mode, and identity diagnostics backed by `PrivilegeAdbManager`.
- Process-wide current Privileged Server Binder state, exposed through `Privilege` global methods.
- `Privilege.isPermissionRestricted()` for checking whether the connected privileged server is subject to permission restrictions. Root servers return `false` without a permission Binder call.
- UserService entry points for app-defined Binder services: start, bind, and stop.

Advanced entry points:

- `Privilege.nativeStarterPath` for obtaining the installed `libprivkitstarter.so` path that a host can display or execute through its chosen shell environment. The lazy property resolves once per application process.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by `android.permission.INTERACT_ACROSS_USERS_FULL` and trusted-caller UID checks. Running the native starter completes one provider handoff that returns the owner Binder and current in-memory owner-death config.
- Ready-server connection helpers, owner-death reconnect configuration, and raw Binder bridge types for custom diagnostics or low-level Binder validation.

Runtime owns launch correlation, internal server launch command construction, the native starter executable, Root `su` execution, ADB pairing/connect/startup, pending handshakes, protocol validation, current server Binder installation, Privileged Server entry points, and Binder death handling. Public ADB configuration and pairing types live in `priv.kit.core.adb`; raw startup transport SPI and wire protocol live under `priv.kit.core.internal.*`.

ADB startup supports managed ADB recovery as an internal strategy. When `PrivilegeAdbStartOptions.wirelessDebuggingControl` allows it, the merged app manifest still declares `WRITE_SECURE_SETTINGS`, and the app holds that permission, the runtime enables `adb_wifi_enabled`, discovers the dynamic `_adb-tls-connect._tcp` port, starts the server, and disables Wireless Debugging after the start attempt by default. For an explicit static-TCP start, `PrivilegeAdbManager.prepareTcpForStart()` first probes the configured port and only writes `ADB_ENABLED=1` when the listener is unavailable; it does not enable Wireless Debugging. After a Privileged Server connects, the runtime attempts to grant `WRITE_SECURE_SETTINGS` to the owner package when the permission is still declared and the server is root or has `android.permission.GRANT_RUNTIME_PERMISSIONS`, so later starts can use these managed ADB paths. If the permission declaration was removed with manifest merge or the permission is not granted, startup continues with the existing manual Wireless Debugging / TCP behavior.

`Privilege.checkPermission(permName, pkgName, userId = cached current user id)` and `Privilege.grantRuntimePermission(packageName, permissionName, userId = cached current user id)` are thin pass-through calls to server-side `IPackageManager`. They do not add policy, discovery, batching, permission groups, app-ops, install flows, or package management abstractions.

`Privilege.startRoot()`, `Privilege.startAdb()`, external startup, ADB discovery/pairing, TCP-mode operations, and authorization checks are suspend APIs. Blocking transport work runs on the IO dispatcher. Cancellation closes the active process, socket, persistent check session, or mDNS discovery so the owning coroutine retains one continuous lifecycle across discovery, authorization, startup, and cleanup.

`PrivilegeHandshakeProvider` initializes the runtime with the app `Context`, so callers use `Privilege` directly without passing `Context` into start, ADB, native-starter path, or ready-server APIs. The provider remains exported so shell/root/external privileged starters can reach it, but normal apps are stopped by the provider permission. Root, system, shell, and the owner UID are trusted startup identities; the protocol does not claim to distinguish or authenticate different processes sharing one of those privileged identities.

The runtime module carries its own `app_process` server and UserService entry points, and contributes the R8 consumer rule that keeps those `main(String[])` methods.

Configure owner-death behavior through the process-wide `PrivilegeConfig` object. When the app-side owner process dies, the Privileged Server waits for `followDeathDelayMillis` before exiting. The default is 10 minutes. Use `0` to exit immediately.

By default, owner-death reconnect is passive: after the initial handshake, the server registers a `ContentObserver` for the owner package's process-start signal. When the app-side handshake provider is created, it publishes that signal and the retained server retries the Binder handoff. On Android 11 and later the notification uses the no-delay flag so background observer delivery is not deferred. Continuous `/proc` scanning is not part of the normal path; the server falls back to the previous process polling only when observer registration fails.

Set `PrivilegeConfig.activeReconnectOnOwnerDeath = true` only if the server should actively call the app handshake provider while the app process is dead, which may start the app process. This active retry behavior remains separate from the passive `ContentObserver` path and is not replaced by it.

Runtime startup coordination distinguishes a new server's `INITIAL_LAUNCH` handshake from a retained server's `OWNER_RECONNECT` handshake. During the short app-start reconciliation window, an already-connected/ready server or owner reconnect wins before a foreground or silent UI start commits any launch side effect. Once a client start commits its runtime lease, a late owner reconnect is rejected and only the matching initial-launch handshake belongs to that operation. This arbiter is process-local; a multi-process app must designate exactly one app process to initialize Priv Kit and invoke its startup entry points.

The latest owner-death configuration is held in the runtime process and returned by the app-side provider during the initial server handoff. A running server keeps the configuration it received at startup; later `PrivilegeConfig` changes affect the next server start only.

Like shizuku-api, the runtime treats the Privileged Server Binder as a single process-wide handle. A repeated handshake for the same Binder keeps the current global server state; a handshake for a replacement Binder installs the new server state.

`Privilege.getServerInfo()`, project-owned server control calls, and `PrivilegeBinderWrapper` resolve the server Binder through the same global connection. A missing or dead server on a project-owned control call is normalized to `PrivilegeServerUnavailableException`. `PrivilegeBinderWrapper` keeps raw transaction failures unchanged because a forwarded failure cannot reliably identify whether the target Binder or the Privileged Server died.

Hosts can wrap a server-related or UserService Binder invocation with `PrivilegeBinderCall.orElse(...)`. Its fallback receives `PrivilegeBinderCallFailure.ServerUnavailable` for the normalized Privileged Server failure or `PrivilegeBinderCallFailure.BinderDied` for a directly called dead endpoint. Other exceptions propagate unchanged. A fallback means that the result is unknown; mutating calls are not retried automatically because the remote side may have completed the operation before dying.

If a server reports a different protocol or APK classpath identity than the current app runtime, the runtime rejects that Binder handoff. When the caller is a trusted existing server, the app returns the current native starter command so the stale server can replace itself from the current install.

`Privilege.nativeStarterPath` returns the installed native starter SO path and caches it for the current application process. Executing that file enters the same Binder handoff path; the app observes the eventual handoff through `Privilege.serverState` or claims a retained handoff with `connectReadyServer()`. UI modules and host apps add an `adb shell` prefix when they present a development-machine command.

External privileged hosts execute the same native starter. UI-managed external starts receive an internally coordinated native starter command from `priv-ui`; callers outside that flow can pass `Privilege.nativeStarterPath` to their shell runner. For an app-owned Binder bridge, the main process calls `PrivilegeExternalStartup.runThroughBridge(...)` and the privileged endpoint delegates its one start method to `PrivilegeExternalStartupHost`. The runtime owns the `ParcelFileDescriptor` stdout/stderr pipes, bounded transcript, live log forwarding, terminal `ResultReceiver`, timeout, and concurrent-call rejection. `runInCurrentProcess(...)` and `createReceiver(...)` remain lower-level helpers. Shizuku binding and the AIDL declaration stay in the app; the app must keep that Binder endpoint private to trusted callers.

This module does not expose typed Android system service wrappers, UI, Compose, package/input/settings/app-ops/activity facades, or app-defined UserService business methods. UserService bind returns a raw Binder for the app's own AIDL Stub, and system-service access is limited to explicit-name raw Binder transaction bridges plus a small number of explicit framework pass-through calls.
