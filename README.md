# Priv Kit

[README.md](./README.md) | [中文文档](./README-zh.md)

A lightweight Android library with no dependencies. It lets an Android app start its own privileged process through Root or ADB, then call system-level APIs through Binder or UserService.

This project aims to reduce reliance on external authorization apps, so developers can implement self privilege escalation inside their own apps.

Activation methods: Root, wireless ADB, manual shell, external authorization

Usage modes: Binder (local / remote) and UserService (embedded / dedicated process)

## Dependency Setup

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-runtime:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // Optional Compose authorization UI
}
```

Only the compile-time APIs visible after an integrating app references `priv-runtime` / `priv-ui` are currently guaranteed. `priv-runtime` already includes the code needed for Binder, UserService, Root, ADB, manual shell, and external startup entry points. `priv-adb-crypto` is only an independent JVM implementation module and is not part of the Android integration API.

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

Have the user copy and run a command manually:

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.showCommandToUser(commandLine)
```

Pass the startup command to Shizuku UserService or another external startup entry point that can execute code under a compatible privileged identity.

The library provides common APIs for both sides: call `PrivilegeExternalStartup.runInCurrentProcess(...)` inside the privileged process, and use `PrivilegeExternalStartup.createReceiver(...)` in the main process to receive real-time logs. Shizuku UserService binding and AIDL forwarding are handled by the integrating app.

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
