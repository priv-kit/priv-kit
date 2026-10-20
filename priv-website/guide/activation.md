---
description: Start with Privilege UI, or use priv-core to build a custom authorization interface.
---

# Startup methods {#startup-methods}

Using an app and need help starting or pairing? Read the [startup tutorial](./startup-tutorial).

All methods start the app's Privileged Server and return its Binder. They differ only in how they obtain the privileged process.

## Start with Privilege UI {#privilege-ui}

Embed `PrivilegeScaffold` for startup, permissions, pairing, confirmations, and errors. See [Privilege UI](./priv-ui).

The APIs below are for applications that replace the supplied authorization
page.

## Build a custom interface with priv-core {#priv-core-custom-interface}

With `priv-core`, your app implements permission prompts, pairing input, confirmations, polling, and error display.

### Observe the connection state {#connection-state}

`Privilege.serverState` is a process-wide
`StateFlow<PrivilegeServerInfo?>`. A non-null value means the Privileged Server
is connected; `null` means it is disconnected. Every new collector immediately
receives the current value.

`PrivilegeServerInfo` describes the connection. Its nullable `selinuxContext` is cached per server process for diagnostics.

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

### Coordinate an owner-process restart {#owner-process-restart}

When the application deliberately restarts its owner process, schedule that restart first and
then notify the connected server immediately before terminating the process:

```kotlin
scheduleApplicationRestart()
Privilege.prepareOwnerRestart(
    passiveReconnectTimeoutMillis = 10_000,
)
Process.killProcess(Process.myPid())
```

The call waits for server acknowledgement. The plan applies only if the owner dies within five seconds and excludes that death from crash-loop counting. The server waits passively for the requested interval, then resumes configured active reconnect for the remaining `followDeathDelayMillis`. Allow enough time for your app to restart.

### Inspect denied server permissions {#denied-server-permissions}

The UI's **View restricted permissions** action opens a selectable, copyable list. Returning to the page refreshes it.

After the server connects, a custom host can inspect permissions declared by
packages associated with the server UID but denied to the server process:

```kotlin
val deniedPermissions = withContext(Dispatchers.IO) {
    Privilege.getDeniedServerPermissions()
}
```

Results are deduplicated and sorted, excluding permissions undefined on the device. Root returns an empty list. Package lookup failures on non-root servers are reported as errors.

Access also depends on AppOps, SELinux, and service-specific rules. Use `Privilege.checkServerPermission(permission)` to check one Android permission.

#### Redmi K40 example {#denied-permissions-device-example}

These results were captured on the same device with the server running as Shell (UID `2000`):

| Item | Test environment |
| --- | --- |
| Device market name | Redmi K40 (model `M2012K11AC`) |
| System name and version | Xiaomi HyperOS 1.0, `OS1.0.10.0.TKHCNXM` |
| Android version | Android 13 (API 33) |

After connecting the server, call the method from a coroutine and print one permission per line:

```kotlin
val deniedPermissions = withContext(Dispatchers.IO) {
    Privilege.getDeniedServerPermissions()
}
println(deniedPermissions.joinToString("\n"))
```

The two outputs below correspond to enabling and disabling this switch:

**Developer options - USB debugging (Security settings) / Allow modifying permissions or simulating
input via USB debugging**. On the tested device, its Chinese label and description are
**开发者选项 - USB调试（安全设置） / 允许通过USB调试修改权限或模拟点击**.

After changing the switch, call the method again for the current result; the privileged process
does not need to restart.

**Restrictions disabled (USB debugging (Security settings) enabled): the returned list is empty (0 denied permissions).**

**Restrictions enabled (USB debugging (Security settings) disabled): 14 permissions are denied.**

```text
android.permission.CALL_PHONE
android.permission.CLEAR_APP_USER_DATA
android.permission.GRANT_RUNTIME_PERMISSIONS
android.permission.INJECT_EVENTS
android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
android.permission.MANAGE_DEVICE_ADMINS
android.permission.READ_CONTACTS
android.permission.REVOKE_RUNTIME_PERMISSIONS
android.permission.SEND_SMS
android.permission.SET_PREFERRED_APPLICATIONS
android.permission.UPDATE_APP_OPS_STATS
android.permission.WRITE_CONTACTS
android.permission.WRITE_SECURE_SETTINGS
android.permission.WRITE_SETTINGS
```

These are denied permissions on this device and OS version. Query the current device rather than hard-coding the list.

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

After `pair()` succeeds, call `startAdb()` to start the server.

When the host declares and already holds `WRITE_SECURE_SETTINGS`, the default
`PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE` policy can temporarily
enable Wireless Debugging, discover the connect port, and disable Wireless
Debugging after the start attempt. `NEVER` leaves the setting untouched.
`REQUIRE` makes managed Wireless Debugging a startup prerequisite.

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
endpoint affects other ADB-backed processes, so a custom host presents its own
confirmation before calling it.
When the source connection port is already known, pass it through
`options = PrivilegeAdbConnectionOptions(port = sourcePort)`.

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

Set `port` to connect directly to that port:

```kotlin
val serverInfo = Privilege.startAdb(
    options = PrivilegeAdbConnectionOptions(
        port = tcpPort,
    ),
)
```

Use `checkTcpAuthorization()` when the host only needs a one-shot status check.
Use `openTcpAuthorizationCheckSession()` for repeated polling. `stopTcp(tcpPort)`
stops the static listener. `restartTcp(tcpPort)` restarts it on the same port.
Both controls can accept `PrivilegeAdbConnectionOptions`; they try the static
endpoint first and only fall back before the control command is dispatched.

### Manual {#manual}

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}
YourApp.showCommandToUser("adb shell $nativeStarterCommand")
```

`Privilege.nativeStarterCommand` returns a device-side command; add `adb shell` for a computer terminal. Resolve it off the main thread because first access inspects APKs. It accepts root (0), system (1000), or shell (2000) and includes the owner user ID when nonzero. See [native library packaging](./getting-started#native-library-packaging).

Re-running the starter kills the old server for this package and Android user, verifies its exit, then starts a replacement. If stopping fails, no replacement starts. Other users' servers are unaffected.

With modern packaging on Android 10 or later, a rendered command can look like:

```shell
adb shell /system/bin/linker64 '/data/app/.../base.apk!/lib/arm64-v8a/libprivkitstarter.so'
```

With legacy packaging it looks like:

```shell
adb shell /data/app/~~-YKUdRFBwGAwYBVzJRt7pA==/priv.kit.sample.debug-A-2guZlsvRZ-9e6xF-K0kQ==/lib/arm64/libprivkitstarter.so
```

### External {#external}

External startup runs the same native command through an authorizer's privileged process.

The authorizer must run the command as root, system, or shell. Your app handles authorization, binding, and access control. The example below uses an already-authorized Shizuku UserService.

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

## Crash logs {#crash-logs}

Configure a preferred directory during application initialization, before starting a server or
first reading `Privilege.nativeStarterCommand`:

```kotlin
PrivilegeConfig.crashLogDirectory = context.getExternalFilesDir("privilege-crashes")
```

The host creates the directory. A null value uses `/data/local/tmp` only. The server receives this
path at launch and refreshes it on owner handshakes; dedicated UserServices inherit it at launch,
before their constructors run. Already-running dedicated processes keep their original directory.
If writing the preferred directory fails, including work-profile storage denied to shell, the
recorder retries `/data/local/tmp`. Writes remain subject to filesystem and SELinux permissions.

Fatal Java/Kotlin exceptions produce one UTF-8 JSON file. The configured directory belongs to one
app and Android user, and files there use `priv-crash_uid<uid>_<yyyyMMddHHmmss>.json`.
The shared fallback directory `/data/local/tmp` uses
`priv-crash_<applicationId>_u<userId>_uid<uid>_<yyyyMMddHHmmss>.json`.
The filename uses the crash time in the device local timezone, with second precision, assuming at most one crash per second for each app/user/UID. Process type, PID,
and timestamps remain in the JSON. The recorder syncs and closes a temporary `.json.tmp` file before atomically renaming it. Scan only
`.json` files; an interrupted write can leave a temporary file. The host owns scanning and cleanup.
Read the app's external directory directly at startup, then scan the fallback directory through
`Privilege.file()` once a privileged connection is available. Root-owned files may require root.

Reports use the public `@Serializable` data class `PrivilegeCrashLog`. Core exposes
`kotlinx-serialization-core` and a generated serializer, but uses Android `JSONObject` to write
reports. To decode with Kotlin JSON, add `kotlinx-serialization-json` to the host:

```kotlin
val json = Json { ignoreUnknownKeys = true }
val report = json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
if (report.schemaVersion == 1) {
    println(report.stackTrace)
}
```

The model contains the host application ID and Android user ID, process UID/PID and type,
optional service class name, recorder initialization and crash times in epoch milliseconds,
thread name, exception type, nullable message, and the stack trace with causes and suppressed
exceptions. It does not contain preceding Logcat messages. Persistence is best effort; both
failed destinations still allow process exit. Failures before launch configuration parsing,
native crashes, and `SIGKILL` are not captured.
