---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService {#user-service}

UserService runs custom code or built-in Binder forwarding in a privileged process.

## Forward Binder without a custom service {#binder-forwarding}

Omit `serviceClassName` to use the built-in forwarding service. No custom service class or business AIDL is needed; the app still supplies the target system interface, such as `ILauncherApps`.

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shell-binder",
        process = PrivilegeUserServiceProcess(uid = 2000),
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
    // Use connection.callingPackageName when the API requires a calling package.
} finally {
    connection.unbind()
}
```

A custom UserService connection supports the same `userService = connection` argument without adding any methods to its business AIDL. `fromBinder(target, userService = connection)` forwards an existing Binder through that process. Lookup and outgoing transactions run in the selected process. Returned Binder objects and callbacks are not recursively wrapped; wrap returned targets explicitly when needed. Unbinding invalidates wrappers using that connection without switching to the main server.

`Privilege.bindUserService()` returns after process startup, service construction, and Binder delivery complete, so the connection can be used immediately. An existing instance may be reused. It does not wait for asynchronous initialization started by custom service code or guarantee that the main Looper has begun dispatching messages.

## Select the process UID {#process-uid}

| `PrivilegeUserServiceProcess.uid` | Execution |
| --- | --- |
| `null` (default) | Dedicated child, inheriting the server identity |
| `-1`, or `EMBEDDED` | Shared server process, using its existing identity |
| `0` | Dedicated root process |
| `1000` | Dedicated system-UID process |
| `2000` | Dedicated shell-UID process |

Changing UID requires a root server; a non-root server can only inherit or request its own UID. Other UID values are rejected. When root switches to UID 2000, the native launcher copies supplementary groups from the running device adbd, sets GID/UID to 2000, and clears capabilities. If the adbd groups cannot be read, startup fails. For UID 1000 it sets GID/UID to 1000 and clears supplementary groups and capabilities. It retains the parent's SELinux domain and does not reproduce the full adb shell or system_server environment.

`connection.uid` reports the actual process UID. `callingPackageName` is `"android"` for UID 1000, `"com.android.shell"` for UID 2000, and `null` otherwise. Pass it to APIs that validate a caller package; the wrapper does not rewrite parcels or bypass other service checks.

Instances are still keyed by class name and tag. Changing `process`, `version`, or `daemon` replaces that instance; use distinct tags to keep multiple identities alive simultaneously. For the built-in service, the class name is null.

## Fix root package identity failures in getShortcuts {#launcherapps-shortcuts}

If `LauncherApps.getShortcuts` forwarded through root fails with `SecurityException("Calling package name mismatch")`, use `process.uid` together with `connection.callingPackageName`. Android 16's `LauncherAppsService.verifyCallingPackage` compares the package's UID to the Binder caller UID without a root exemption. Root UID 0 does not match the host app, `com.android.shell` (2000), or `android` (1000). See [LauncherAppsService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java).

`process = PrivilegeUserServiceProcess(uid = 2000)` selects the outgoing process identity; `userService = connection` routes the transaction through it; `connection.callingPackageName` supplies `"com.android.shell"` as the API's explicit caller package. Changing only the package string or only the process UID leaves a mismatch. UID 1000 with `"android"` also satisfies this package ownership check.

The example uses Android 16's hidden `ILauncherApps.getShortcuts` signature. Supply `query: ShortcutQueryWrapper` and `targetUser: UserHandle`, and provide the matching hidden API access declarations in your app. Run the synchronous Binder call off the main thread. See [ILauncherApps](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/content/pm/ILauncherApps.aidl).

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shortcuts",
        process = PrivilegeUserServiceProcess(uid = 2000),
    ),
)
try {
    val launcherApps = ILauncherApps.Stub.asInterface(
        checkNotNull(
            PrivilegeBinderWrapper.fromSystemService(
                serviceName = "launcherapps",
                userService = connection,
            ),
        ),
    )
    val shortcuts = launcherApps.getShortcuts(
        checkNotNull(connection.callingPackageName),
        query,
        targetUser,
    )
    // Consume shortcuts here.
} finally {
    connection.unbind()
}
```

`callingPackageName` is the caller identity, not the package whose shortcuts you want; select that package in `query`. An existing `LauncherApps` from `Context.getSystemService` is not automatically redirected and still supplies its Context's package name. Use the wrapped `ILauncherApps` above.

This resolves the UID/package mismatch, not every shortcut failure. The subsequent host-permission check accepts `ACCESS_SHORTCUTS`, which AOSP Shell declares; target-user access, user unlock state, query flags, and device policies still apply. See [ShortcutService](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/services/core/java/com/android/server/pm/ShortcutService.java) and the [Shell manifest](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/packages/Shell/AndroidManifest.xml). The root-to-shell path requires a root server and readable adbd groups as described above; selecting UID 2000 does not turn the process into the SELinux shell domain.

## Define the AIDL contract {#define-aidl-contract}

Use transaction code `16777114` for the destroy method:

```java
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## Implement the service {#implement-service}

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
        if (!PrivilegeUserServiceEnvironment.isEmbedded) {
            exitProcess(0)
        }
    }
}
```

The runtime supports a no-argument constructor or a `Context` constructor.
Dedicated processes prefer application initialization and fall back to a
package context. Embedded services receive a package context.

## Use a dedicated process {#dedicated-process}

This is the default. The service runs in a separate `app_process` child:

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        tag = "main",
        version = 1,
    )

    Privilege.startUserService(spec)

    val connection = Privilege.bindUserService(spec)
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }

    Privilege.stopUserService(spec)
}
```

`startUserService`, `bindUserService`, and `stopUserService` suspend and support cancellation. Cancellation removes pending work and unaccepted resources. `connection.unbind()` is idempotent and completes cleanup in a non-cancellable context.

Each instance is identified by `serviceClassName + tag`. The `version` value
expresses whether the runtime can reuse the instance or replaces it. Change the
value when the implementation becomes incompatible.

## Use an embedded service {#embedded-service}

Set `process = PrivilegeUserServiceProcess.EMBEDDED` to run directly inside the Privileged Server, saving process startup and memory overhead by sharing the existing process:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    process = PrivilegeUserServiceProcess.EMBEDDED,
)
```

Embedded services share the server process. Construction is asynchronous and cancellable.

Invalid UserService state and failed operation responses use `IllegalStateException`.
Server unavailability, Binder transport errors, and coroutine cancellation retain their
own exception types.
Manager or service endpoint death propagates as `DeadObjectException`. The internal
response protocol preserves connection-failure categories across processes, so
`PrivilegeBinderCall.orElse(...)` can handle them without inspecting nested causes.

## Detect the execution environment {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` is `true` inside the shared server process and `false` in a dedicated child process. The value is fixed for that process.

Check this before terminating a process: embedded services must only release their own resources and must not call `exitProcess`, which terminates the entire privileged process. Select the mode with `PrivilegeUserServiceSpec.process`.
