# priv-core

`priv-core` owns the app-side privileged runtime. Its public package root is
`priv.kit.core`.

## Main APIs

- `Privilege.startRoot()` and `Privilege.startAdb()` start the Privileged Server.
- `Privilege.createAdbManager()` handles Wireless ADB pairing, authorization, and TCP mode.
- `Privilege.serverState` exposes the process-wide connection.
- `Privilege.file(absolutePath)` runs basic file operations in the server.
- UserService APIs start, bind, unbind, and stop app-defined Binder services.
- `PrivilegeBinderWrapper` forwards raw Binder transactions to explicit endpoints.

`PrivilegeServerInfo` describes the connected server. Its lifecycle Binder is a stable death token
for that server process. The nullable `selinuxContext` is read once per server process and reused
across owner reconnects. It is diagnostic data. Construction and `copy` stay internal, while the
data class provides structural equality and a useful `toString`.

## Startup and connection

Root, ADB, manual commands, and external bridges all execute the same native starter and finish
through the same Binder handshake. The handshake installs the control, lifecycle, file-system, and
UserService-manager Binders as one connection snapshot.

`Privilege.nativeStarterCommand` resolves the device-side command once per app process. Android 10
and later can run an uncompressed starter directly from the APK through the platform linker;
legacy packaging uses the extracted file in `nativeLibraryDir`. Initial resolution inspects the
installed APKs, so callers resolve it on a worker thread and add `adb shell` when presenting it to
a development machine.

Each server has a package/user-scoped process token. Re-running the starter verifies and stops the
matching old process before creating its replacement. Failures to inspect or stop that process are
reported as `PrivilegeExistingServerStopException` by Core-managed startup.

The server follows the app-side owner Binder. After owner death it waits for
`PrivilegeConfig.followDeathDelayMillis`, which defaults to ten minutes. The normal reconnect path
waits for the app process-start signal; `activeReconnectOnOwnerDeath` opts into direct provider
retries that may start the app process. A multi-process app chooses one process to initialize the
runtime and invoke startup APIs.

## ADB

A null `PrivilegeAdbConnectionOptions.port` discovers the Wireless Debugging endpoint. A concrete
port connects to static TCP directly. The stored ADB key is decoded once into process-wide key and
TLS material.

With `WRITE_SECURE_SETTINGS`, managed Wireless Debugging can temporarily enable
`adb_wifi_enabled` for discovery and restore it after startup. Static-TCP recovery probes the saved
port and can restore the core ADB service through `ADB_ENABLED=1`; it leaves Wireless Debugging
unchanged. Stop and restart commands try the active static endpoint first, with fallback available
until command dispatch begins.

## File proxy

`PrivilegeFile` is an immutable absolute-path handle. Familiar Boolean methods retain
`java.io.File` semantics, while server availability failures use
`PrivilegeServerUnavailableException`. `replaceAtomically` maps to same-filesystem `Os.rename` and
preserves errno through the exception cause.

File content streams through reliable pipes while the privileged target descriptor stays in the
server. Directory walks are cold, unsorted, weakly consistent depth-first flows. Recursive deletion
normalizes the explicit target, rejects filesystem root, traverses with `SecureDirectoryStream`,
and can leave a partial tree after cancellation or failure. Transfers, walks, and recursive deletes
use bounded server-side concurrency.

## Binder and UserService

Permission checks and runtime permission grant/revoke methods are thin framework pass-throughs.
Domain policy stays with the integrating app.

UserService lifecycle methods are suspending operations backed by a bounded asynchronous Binder
protocol. Cancellation removes pending work and unaccepted resources. Connection unbind is
idempotent and completes its cleanup in a non-cancellable context.

External privileged hosts can execute the native starter through
`PrivilegeExternalStartup.runThroughBridge(...)`. Core owns command execution, pipes, transcript,
completion, timeout, and concurrent-call handling. Third-party binding and app AIDL remain in the
app or an optional integration.

## Module boundary

This module contains runtime infrastructure and low-level Binder/file primitives. Android
system-service domain APIs, app-owned AIDL behavior, and authorization UI live in the integrating
app or `priv-ui`.
