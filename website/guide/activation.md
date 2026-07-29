---
description: Start with Privilege UI, or use priv-core to build a custom authorization interface.
---

# Startup methods {#startup-methods}

Every startup method starts the same app-owned Privileged Server and converges
on the same Binder handoff. The difference is how the initial privileged
process is created.

## Start with Privilege UI {#privilege-ui}

Most applications should use `priv-ui`. Embed `PrivilegeScaffold` to get the
Root, Wireless Debugging, TCP/IP, Manual, and external-provider flows with their
status, permissions, pairing, confirmations, and error presentation already
coordinated. See [Privilege UI](./priv-ui) for setup and configuration.

The APIs below are for applications that replace the supplied authorization
page.

## Build a custom interface with priv-core {#priv-core-custom-interface}

When an application uses `priv-core` directly, the host owns every permission
prompt, pairing input, confirmation surface, polling loop, and error state.
`priv-core` provides the runtime and transport operations only.

### Observe the connection state {#connection-state}

`Privilege.serverState` is a process-wide
`StateFlow<PrivilegeServerInfo?>`. A non-null value means the Privileged Server
is connected; `null` means it is disconnected. Every new collector immediately
receives the current value.

#### Application-wide observation {#application-observation}

When connection changes must trigger work for the lifetime of the application
process, collect from an application-owned coroutine scope:

```kotlin
val appScope = MainScope()

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            Privilege.serverState.collect { serverInfo ->
                if (serverInfo != null) {
                    // Connected. Initialize application-level privileged features.
                } else {
                    // Disconnected. Suspend features that require the server.
                }
            }
        }
    }
}
```

#### Screen-local rendering {#screen-state}

When a Compose screen only needs to render the current state, use a
lifecycle-aware collector:

```kotlin
val serverInfo by Privilege.serverState.collectAsStateWithLifecycle()
```

### Root {#root}

```kotlin
val serverInfo = Privilege.startRoot()
```

Root startup checks the available `su` path, launches the shared server command,
and waits for the normal Binder handoff.

### ADB {#adb}

ADB supports two modes: Wireless Debugging and a static TCP/IP port.

#### Wireless Debugging {#wireless-debugging}

Wireless Debugging requires Android 11 or later. Priv Kit stores one ADB key for
the application. The device must authorize that key before it can start a
Privileged Server.

##### Pair the application {#pairing}

Ask the user to open **Developer options > Wireless debugging > Pair device
with pairing code**. While the pairing screen is open, pass its six-digit code
to `PrivilegeAdbManager.pair()`:

```kotlin
val adbManager = Privilege.createAdbManager()

val pairingResult = adbManager.pair(
    pairingCode = pairingCode,
)
```

`pair()` discovers the Wireless Debugging pairing port by default. A host that
already discovered the port can make the endpoint explicit:

```kotlin
val pairingPort = adbManager.discoverPairingPort()

adbManager.pair(
    pairingCode = pairingCode,
    port = pairingPort,
)
```

Use `checkPairing()` to check whether the persisted application key is already
authorized:

```kotlin
val pairing = adbManager.checkPairing()
if (!pairing.paired) {
    // Show the pairing flow before starting.
}
```

For repeated status checks, `openPairingCheckSession()` keeps one connection
alive between calls. Close the session when polling stops.

##### Start with Wireless Debugging {#wireless-debugging-start}

```kotlin
val serverInfo = Privilege.startAdb()
```

Pairing and startup are separate operations, so a successful `pair()` does not
start the server.

When the host declares and already holds `WRITE_SECURE_SETTINGS`, the default
`PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE` policy can temporarily
enable Wireless Debugging, discover the connect port, and disable Wireless
Debugging after the start attempt. Use `NEVER` when the application must not
change this setting. Use `REQUIRE` when startup should fail if Wireless
Debugging is off and Priv Kit cannot enable it.

After the Privileged Server connects, the runtime attempts to grant
`WRITE_SECURE_SETTINGS` to the owner app when the permission remains declared
and the server is root or holds
`android.permission.GRANT_RUNTIME_PERMISSIONS`. Without this capability, the
user must turn on Wireless Debugging before discovery.

On Android releases that require runtime approval for local network access, a
host must request `ACCESS_LOCAL_NETWORK` before pairing or startup.

#### TCP/IP startup {#tcp-ip}

A TCP/IP start connects to a fixed local ADB port instead of discovering the
dynamic Wireless Debugging connect port. The default static port is available
as `PRIVILEGE_ADB_DEFAULT_TCP_PORT`.

##### Open or restore the static port {#tcp-ip-setup}

If the port is not configured yet, switch an already authorized ADB endpoint to
TCP/IP mode:

```kotlin
val tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT
val adbManager = Privilege.createAdbManager()

adbManager.switchToTcp(tcpPort = tcpPort)
```

`switchToTcp()` needs an authorized Wireless Debugging or existing TCP
connection from which it can issue `adb tcpip`. Starting or restarting this
endpoint affects other ADB-backed processes. `priv-core` does not present a
confirmation surface, so the host must obtain confirmation before calling it.
When the source connection port is already known, pass it through
`options = PrivilegeAdbStartOptions(port = sourcePort)`.

When a static port was configured earlier, prepare it before startup:

```kotlin
val authorization = adbManager.prepareTcpForStart(tcpPort = tcpPort)
```

`prepareTcpForStart()` probes the port. If a persisted port matches but `adbd`
is no longer listening, Priv Kit can restore the core ADB service when the
application has managed ADB capability. It does not enable Wireless Debugging.

If the result is `PrivilegeAdbAuthorizationStatus.UNAUTHORIZED`, request
authorization and wait for the user to accept the system prompt:

```kotlin
val request = adbManager.requestTcpAuthorization(tcpPort = tcpPort)
check(request.authorized) {
    request.failureMessage ?: "ADB authorization was not completed"
}
```

##### Start on the static port {#tcp-ip-start}

Pass the port explicitly. A non-null `port` skips Wireless Debugging discovery:

```kotlin
val serverInfo = Privilege.startAdb(
    options = PrivilegeAdbStartOptions(
        port = tcpPort,
    ),
)
```

Use `checkTcpAuthorization()` when the host only needs a one-shot status check.
Use `openTcpAuthorizationCheckSession()` for repeated polling. Call
`stopTcp(tcpPort)` to return `adbd` to USB mode.

### Manual {#manual}

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}
YourApp.showCommandToUser("adb shell $nativeStarterCommand")
```

`priv-core` returns a device-side command. On Android 10 and later it can use the
platform linker to run the starter directly from an APK, or execute the
installed SO when legacy packaging extracted it. The host adds `adb shell` when
presenting the command for a development machine. The starter only runs as root
(UID 0), system (UID 1000), or shell (UID 2000). See
[native library packaging](./getting-started#native-library-packaging) for the
Android-version requirements. Resolve the command off the main thread because
first access inspects the installed APKs. User 0 uses the default owner scope
without an owner-user environment variable. A non-primary Android user is
embedded explicitly, so the command remains correctly scoped when it is
executed while the application process is not running.

Running the starter again first sends `SIGKILL` to the exact owner-scoped
server process and verifies that it exited. Its readable name ends with
`<package>:priv-server` for user 0 and adds `-u<ownerUserId>` only for a
non-primary user. An internal package/user token keeps discovery scoped even
when only `/proc/<pid>/comm` is readable.
Only then is the replacement process created. If the current root, system, or
shell identity cannot kill the old process, the command fails and does not
start a second server. A package installed for another Android user has a
different scoped process name and is not selected.

With modern packaging on Android 10 or later, a rendered command can look like:

```shell
adb shell /system/bin/linker64 '/data/app/.../base.apk!/lib/arm64-v8a/libprivkitstarter.so'
```

With legacy packaging it looks like:

```shell
adb shell /data/app/~~-YKUdRFBwGAwYBVzJRt7pA==/priv.kit.sample.debug-A-2guZlsvRZ-9e6xF-K0kQ==/lib/arm64/libprivkitstarter.so
```

### External {#external}

External startup is an extension of `Manual`. Instead of asking the user to run
the native starter command, the application uses an external authorizer to
execute the same command inside its privileged process. Both methods enter the
same Privileged Server startup and Binder handoff path.

The external authorizer can be any app-owned privileged entry capable of
running that command as root, system, or shell. The application owns third-party
authorization, binding, and access control, while `priv-core` supplies the command bridge.
The following example assumes Shizuku is already available and authorized. Its
key step is a Shizuku UserService that exposes the Priv Kit startup bridge.

#### Define the UserService {#define-user-service}

The app-owned AIDL carries the command, output pipes, and completion receiver
into the Shizuku UserService:

```java
interface IPrivilegeShizukuStartService {
    void start(
        String commandLine,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        in ResultReceiver resultReceiver
    ) = 1;
    void destroy() = 16777114;
}
```

Implement that endpoint with `PrivilegeExternalStartupHost`:

```kotlin
class PrivilegeShizukuStartService @Keep constructor() :
    IPrivilegeShizukuStartService.Stub() {
    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(commandLine, stdout, stderr, resultReceiver)
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
```

#### Bind the UserService and start {#bind-user-service}

Describe the Shizuku UserService with a stable tag and a version that changes
when its implementation or AIDL contract becomes incompatible:

```kotlin
val args = Shizuku.UserServiceArgs(
    ComponentName(
        context.packageName,
        PrivilegeShizukuStartService::class.java.name,
    ),
)
    .daemon(false)
    .tag("priv-kit-external-start")
    .processNameSuffix("priv-kit-shizuku-start")
    .version(1)

Shizuku.bindUserService(args, serviceConnection)
```

After `ServiceConnection` returns the AIDL interface, pass its `start()` method
to the Priv Kit bridge:

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}

PrivilegeExternalStartup.runThroughBridge(
    commandLine = nativeStarterCommand,
    bridge = { commandLine, stdout, stderr, resultReceiver ->
        shizukuService.start(commandLine, stdout, stderr, resultReceiver)
    },
)
```

Close the connection with `Shizuku.unbindUserService(...)` when the request is
finished. A complete implementation is available in the sample:
[starter](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleShizukuExternalStarter.kt),
[privileged endpoint](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleShizukuStartService.kt),
and [AIDL contract](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/aidl/priv/kit/sample/startup/IPrivilegeSampleShizukuStartService.aidl).
