# priv-core

`priv-core` owns the app-side privileged runtime. Its public package root is
`priv.kit.core`.

## Main APIs

- `Privilege.startRoot()` and `Privilege.startAdb()` start the Privileged Server.
- `Privilege.createAdbManager()` handles Wireless ADB pairing, authorization, and TCP mode.
- `Privilege.serverState` exposes the process-wide connection.
- `Privilege.prepareOwnerRestart(...)` coordinates an application-managed owner-process restart.
- `Privilege.file(absolutePath)` runs basic file operations in the server.
- `Privilege.startCommand(...)` starts a bounded, non-interactive server-side process whose
  stdout and stderr can be streamed or captured once.
- UserService APIs start, bind, unbind, and stop app-defined Binder services.
- `PrivilegeBinderWrapper` forwards raw Binder transactions to explicit endpoints.

`PrivilegeServerInfo` describes the connected server. Its lifecycle Binder is a stable death token
for that server process. The nullable `selinuxContext` is read once per server process and reused
across owner reconnects. It is diagnostic data. Construction and `copy` stay internal, while the
data class provides structural equality and a useful `toString`.

## Startup and connection

Root, ADB, manual commands, and external bridges all execute the same native starter and finish
through the same Binder handshake. The handshake installs the control, lifecycle, file-system,
command-executor, and UserService-manager Binders as one connection snapshot.

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
retries that may start the app process. Three owner deaths within sixty seconds open an internal
crash-loop circuit and downgrade active retries to the normal passive path. A manually restarted
owner can still reconnect, and a sixty-second stable owner session resets the circuit. A
multi-process app chooses one process to initialize the runtime and invoke startup APIs. Changes to
either owner-death setting are pushed to a connected server as one snapshot and apply to the next
owner death; an already-running reconnect flow keeps the snapshot captured when it started. Before
an application-managed process restart, `Privilege.prepareOwnerRestart(...)` arms a five-second
one-shot plan. A matching owner death is excluded from crash-loop counting and starts with the
requested passive reconnect interval. If the owner does not return in that interval, configured
active reconnect resumes for the remainder of the original follow-death deadline.

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
and can leave a partial tree after cancellation or failure. Walks can prune directory subtrees with
case-sensitive basename globs; matching directories remain in the flow. Dynamic traversal policy
belongs in the app or a UserService. Transfers, walks, and recursive deletes use bounded server-side
concurrency.

## Binder and UserService

Permission checks and runtime permission grant/revoke methods are thin framework pass-throughs.
`Privilege.getDeniedServerPermissions()` returns the distinct, sorted manifest permissions that
packages associated with the server UID declare but the server PID/UID is denied. It does not
inspect AppOps, SELinux, or service-specific authorization, and an empty result is not a general
capability guarantee. Domain policy stays with the integrating app.

UserService lifecycle methods are suspending operations backed by a bounded asynchronous Binder
protocol. Cancellation removes pending work and unaccepted resources. Connection unbind is
idempotent and completes its cleanup in a non-cancellable context.

External privileged hosts can execute the native starter through
`PrivilegeExternalStartup.runThroughBridge(...)`. Core owns command execution, pipes, transcript,
completion, timeout, and concurrent-call handling. Third-party binding and app AIDL remain in the
app or an optional integration.

## Command execution

`Privilege.startCommand(...)` executes an argument list directly with `ProcessBuilder`; it never
adds a shell. The suspending start call returns only after the process has started. Its
`PrivilegeCommandProcess` then permits exactly one consumption path: `stream()` emits distinct
stdout and stderr byte chunks followed by one exit event, while `awaitResult()` drains both streams
and returns bounded captures. Both paths read stdout and stderr concurrently.

The internal command Binder exchanges only framework values. Requests and completion metadata use
`Bundle`, `IBinder`, and `ResultReceiver`; stdout and stderr use separate reliable
`ParcelFileDescriptor` pipes. Four commands may run concurrently with no waiting queue. Timeout,
explicit cancellation, owner death, and server shutdown terminate active processes and close both
output paths. The API has no stdin, PTY, terminal emulation, or daemon management.

## Module boundary

This module contains runtime infrastructure and low-level Binder/file primitives. Android
system-service domain APIs, app-owned AIDL behavior, and authorization UI live in the integrating
app or `priv-ui`.
