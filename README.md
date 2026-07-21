# Priv Kit

[README.md](./README.md) | [中文文档](./README-zh.md)

A lightweight Android library with no dependencies. It lets an Android app start its own privileged process through Root or ADB, then call system-level APIs through Binder or UserService.

This project aims to reduce reliance on external authorization apps, so developers can implement self privilege escalation inside their own apps.

Activation methods: Root, wireless ADB, manual shell, external authorization

Usage modes: Binder (local / remote) and UserService (embedded / dedicated process)

## Dependency Setup

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // Optional Compose UI and silent replay
}
```

Only the compile-time APIs visible after an integrating app references `priv-core` / `priv-ui` are currently guaranteed. `priv-core` already includes the code needed for Binder, UserService, Root, ADB, manual shell, and external startup entry points. `priv-shared` is an Android implementation dependency and `priv-adb-crypto` is a JVM implementation dependency; both are resolved transitively at runtime, are not direct Android integration APIs, and carry no compatibility guarantee for direct consumers.

You must configure [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass).

```kotlin
HiddenApiBypass.addHiddenApiExemptions("L")
```

## Activation

Start directly on a Root device:

```kotlin
val serverInfo = Privilege.startRoot()
```

Start through ADB Wireless Debugging or ADB TCP:

```kotlin
val serverInfo = Privilege.startAdb()
```

If the integrating app still declares and has been granted `WRITE_SECURE_SETTINGS`, ADB startup will temporarily enable Wireless Debugging when it needs a dynamic wireless debugging port, then turn it off after startup. Before an explicit static-TCP start, `PrivilegeAdbStarter.prepareTcpForStart()` can also re-enable the core ADB service without enabling Wireless Debugging when a persisted TCP port remains configured but `adbd` is no longer listening. After a Privileged Server is connected, the runtime attempts to grant that owner-app startup permission when the permission is still declared and the server is root or has `android.permission.GRANT_RUNTIME_PERMISSIONS`, so later starts can use these managed ADB paths. Without that manifest declaration, granted permission, or server grant capability, it falls back to the manual Wireless Debugging, pairing, or TCP-port paths. This is only an internal startup strategy and does not expose a general Settings API; PackageManager support is limited to explicit `checkPermission(...)` and `grantRuntimePermission(...)` pass-through calls.

The built-in `priv-ui` surface requires one-shot confirmation before it issues `adb tcpip`, because restarting ADB terminates other processes that depend on ADB. Cancelling leaves ADB unchanged. Reusing or recovering a persisted static port does not require this confirmation and can start without Wi-Fi when the runtime has permission to restore the core ADB service.

Blocking startup, discovery, and authorization checks preserve the thread interrupt flag and propagate `InterruptedException`. Java callers should handle that checked exception; coroutine callers may wrap these APIs with an interruptible dispatcher to use interruption as cooperative cancellation.

When using `priv-ui`, keep one application-scoped `PrivilegeUiConfig` and pass the same instance to the foreground ViewModel and to the optional desired-state-gated replay entry point:

```kotlin
val serverInfo = PrivilegeUi.startSilentlyIfEnabled(
    context = applicationContext,
    config = privilegeUiConfig,
)
```

The library initialization provider stores the desired privilege state as exactly one byte, `1` or `0`, in `filesDir/.priv-kit/ui-desired-enabled`. Every accepted `INITIAL_LAUNCH` connection writes `1`, including a server started from a copied external shell command. `OWNER_RECONNECT`, disconnection, process death, and failed replay do not clear it. Only a confirmed stop from the built-in UI or its disconnected-state "Disable automatic recovery" action writes `0`. When the desired state is enabled but the server is disconnected, the built-in UI shows that action in a warning card.

A copied external command enables the desired-state latch but does not invent or replace replay-method history. If that server later stops and no UI-confirmed method is available, gated recovery returns `null` and leaves the warning visible.

After a foreground UI-managed start reaches a successful Binder connection, `priv-ui` also stores one raw method ID (`root`, `adb-wireless`, `adb-tcpip`, or `external:<providerId>`) in `filesDir/.priv-kit/ui-start-method`. `startSilentlyIfEnabled(...)` first checks the desired-state byte, then uses the same exact replay behavior as `startSilently(...)`. Silent startup returns `null` without requesting Android permissions, showing UI, or falling back to another method. Foreground and silent starts are process-locally exclusive: accepted foreground startup effects retain owner-scoped interactive leases until completion, while the built-in UI disables side-effecting entries during a silent attempt and refreshes runtime state before enabling them again. A root manager may still show its own authorization UI if its remembered grant is no longer valid. See the [priv-ui guide](priv-ui/README.md#foreground-silent-and-owner-reconnect-startup) for method-specific behavior.

Have the user copy and run a command manually:

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.showCommandToUser(commandLine)
```

Pass the startup command to Shizuku UserService or another external startup entry point that can execute code under a compatible privileged identity.

The runtime owns the reusable bridge mechanics. The main process calls `PrivilegeExternalStartup.runThroughBridge(...)`, while the privileged endpoint delegates its single start method to `PrivilegeExternalStartupHost`; the runtime manages `ParcelFileDescriptor` pipes, live logs, completion, timeouts, and concurrent-call rejection. `runInCurrentProcess(...)` and `createReceiver(...)` remain available as lower-level helpers. The integrating app keeps only its Shizuku UserService binding and app-owned AIDL contract, and must restrict access to that Binder endpoint.

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.bindUserServiceAndRun(commandLine)
```

After the service connects, you can use Binder/UserService.

## Binder

Current process binder:

```kotlin
val activityBinder = PrivilegeBinderWrapper.fromSystemService("activity")
val activityManager = IActivityManager.Stub.asInterface(activityBinder)
Log.d("activity", activityManager.getTasks(1).toString()) // Gets the foreground app UI on the device
```

Some services can only be obtained from the shell process:

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
```

## UserService

Define a custom AIDL interface and use `16777114` to mark the destroy method. This method is called when the user service is stopped externally.

```aidl
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

Implement the service:

```kotlin
class MyPrivilegeService private constructor(
    private val context: Context?,
) : IMyPrivilegeService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)

    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        exitProcess(0)
    }
}
```

Dedicated process UserService: the default mode. The service runs in a separate `app_process` child process.

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
)
Privilege.startUserService(spec)
Privilege.bindUserService(spec).use { connection ->
    val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
    service.getUid()
}
Privilege.stopUserService(spec)
```

Embedded UserService: the service runs directly inside the Privileged Server process. It does not create a new process and is suitable for lightweight logic.

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    embedded = true,
)
```

Note: when stopping an embedded UserService, the `destroy` method is still called, but it should not call `exitProcess(0)` internally. Otherwise, the entire server process will be destroyed.

## More Documentation

- [Detailed project guide](docs/README.md)
- [Project constitution](docs/project-constitution.md)
- [Architecture design](docs/architecture.md)
- [Module guide](docs/modules.md)
- [Third-party notices](docs/third-party-notices.md)
