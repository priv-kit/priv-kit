---
description: Compare Root, ADB, manual shell, and external activation paths in Priv Kit.
---

# Choose an activation path

Every activation path starts the same app-owned Privileged Server and converges
on the same Binder handoff. The difference is how the initial privileged
process is created.

## Compare the paths

| Path | Best fit | User interaction |
| --- | --- | --- |
| Root | Devices with an available root manager | The root manager may request authorization |
| Wireless ADB | Android devices with Wireless Debugging | Pairing or authorization may be required |
| Static TCP ADB | A known ADB TCP endpoint | May require one-time confirmation before restarting ADB |
| Manual shell | A developer or user can run a command | The app displays a command to copy |
| External bridge | Shizuku or another trusted app-owned entry point | Defined by the host integration |

## Root

```kotlin
val serverInfo = Privilege.startRoot()
```

Root startup checks the available `su` path, launches the shared server command,
and waits for the normal Binder handoff.

## ADB

```kotlin
val serverInfo = Privilege.startAdb()
```

ADB startup can use Wireless Debugging or an explicitly configured static TCP
endpoint. When the host still declares and already holds
`WRITE_SECURE_SETTINGS`, the runtime can temporarily enable Wireless Debugging
to obtain a dynamic port, then disable it after startup. Before an explicit
static TCP start, `PrivilegeAdbManager.prepareTcpForStart()` can restore the
core ADB service while leaving Wireless Debugging off when a persisted port
still exists but `adbd` is no longer listening.

After the Privileged Server connects, the runtime attempts to grant this
startup permission to the owner app when the permission remains declared and
the server is root or holds `android.permission.GRANT_RUNTIME_PERMISSIONS`.
When managed capability is unavailable, startup continues through the manual
pairing and port paths. The public surface stays focused on startup, with
explicit PackageManager pass-through support for `checkPermission(...)` and
`grantRuntimePermission(...)`.

Starting or restarting a static endpoint through `adb tcpip` affects other
ADB-backed processes. The built-in UI therefore requires one-shot confirmation
before issuing that operation. Cancelling leaves ADB unchanged. Reusing or
recovering a persisted static port proceeds directly and can use the restored
core ADB service even when Wi-Fi is unavailable.

## Manual shell

```kotlin
val nativeStarterPath = Privilege.nativeStarterPath
YourApp.showCommandToUser("adb shell $nativeStarterPath")
```

Core returns the installed native starter SO path. The host adds `adb shell`
when presenting a command that runs from a development machine.

The built-in UI displays the command directly. A rendered command looks like:

```shell
adb shell /data/app/~~-YKUdRFBwGAwYBVzJRt7pA==/priv.kit.sample.debug-A-2guZlsvRZ-9e6xF-K0kQ==/lib/arm64/libprivkitstarter.so
```

The `/data/app/...` path varies by device, build, and installation. Read it from
`Privilege.nativeStarterPath` when the host prepares the command. The value is
resolved once per application process.

## External authorization

The runtime provides bridge mechanics, while the host app owns third-party
binding and access control:

```kotlin
val nativeStarterPath = Privilege.nativeStarterPath
YourApp.bindUserServiceAndRun(nativeStarterPath)
```

For an app-owned bridge, the main process can use
`PrivilegeExternalStartup.runThroughBridge(...)`, and the privileged endpoint
delegates its single start method to `PrivilegeExternalStartupHost`. The runtime
manages `ParcelFileDescriptor` log pipes, live output, completion, timeouts, and
enforces a single active bridge call. `runInCurrentProcess(...)` and
`createReceiver(...)` remain available as lower-level helpers. Shizuku binding,
the host AIDL contract, and access control remain in the app.

## Failure and cancellation

Root, ADB, external startup, pairing, discovery, TCP operations, and
authorization checks are suspend APIs. A cancellation request closes active
transport resources and resumes the same coroutine with cancellation. The
runtime keeps the selected activation path and reports an uncertain detached
server launch to the caller.
