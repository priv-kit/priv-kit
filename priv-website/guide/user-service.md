---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService {#user-service}

Run your own AIDL service or forward Binder calls through a privileged process.

## Default: embedded in the server {#default-process}

`PrivilegeUserServiceSpec` defaults to `EMBEDDED`, reusing the privileged server process and UID to reduce startup, memory, and process management overhead. Choose `INHERIT` for process isolation, or select a different UID.

Keep business state in the app's main process. See the [design philosophy](./what-is-priv-kit#design-philosophy).

## Forward Binder without a custom service {#binder-forwarding}

Omit `serviceClassName` to use built-in Binder forwarding. Supply the target Android interface in your app.

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "binder",
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
} finally {
    connection.unbind()
}
```

System service lookup runs in the app process by default; UserService forwards the calls. Pass `source = PrivilegeSystemServiceSource.REMOTE_PROCESS` for remote lookup. Custom UserServices also support this forwarding.

## Select the process UID {#process-uid}

| `PrivilegeUserServiceProcess` | Execution |
| --- | --- |
| `EMBEDDED` (default) | Shared privileged server process and UID |
| `INHERIT` | Dedicated process inheriting the server UID |
| `ROOT` | Dedicated root process (UID `0`) |
| `SYSTEM` | Dedicated system process (UID `1000`) |
| `SHELL` | Dedicated shell process (UID `2000`) |

Changing UID usually requires root privileges; `bindUserService()` throws if it fails. The process keeps the server’s SELinux domain.

When switching to shell, the launcher tries to copy adbd’s supplementary groups. If adbd is unavailable or unreadable, startup continues without supplementary groups; operations that need those groups may fail.

## Fix root package identity failures in getShortcuts {#launcherapps-shortcuts}

In AOSP Android 17, [`LauncherAppsService`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/pm/LauncherAppsService.java#623) calls `ensureShortcutPermission()` from `getShortcuts()`, then checks the package through `verifyCallingPackage()`. The relevant source is:

```java
if (!mPackageManagerInternal.isSameApp(callingPackage,
        PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_UNINSTALLED_PACKAGES,
        callerUid, UserHandle.getUserId(callerUid))) {
    throw new SecurityException("Calling package name mismatch");
}
```

[`isSameApp()`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/services/core/java/com/android/server/pm/PackageManagerService.java#7618) first looks up the package UID:

```java
Computer snapshot = snapshot();
int uid = snapshot.getPackageUid(packageName, flags, userId,
        Process.isPrivateComputeCoreUid(callingUid));
return UserHandle.isSameApp(uid, callingUid);
```

[`UserHandle.isSameApp()`](https://android.googlesource.com/platform/frameworks/base/+/android-17.0.0_r1/core/java/android/os/UserHandle.java#221) then compares their app IDs:

```java
public static boolean isSameApp(int uid1, int uid2) {
    return getAppId(uid1) == getAppId(uid2);
}
```

Root (UID `0`) is a special system identity. No `callingPackage` has `uid=0`, so the package identity check fails.

Use both a shell process and the shell package name:

```kotlin
val connection = Privilege.bindUserService(
    PrivilegeUserServiceSpec(
        tag = "shortcuts",
        process = PrivilegeUserServiceProcess.SHELL, // Caller UID: 2000
    ),
)
try {
    val binder = PrivilegeBinderWrapper.fromSystemService(
        serviceName = "launcherapps",
        userService = connection,
    )
    val launcherApps = ILauncherApps.Stub.asInterface(checkNotNull(binder))
    val shortcuts = launcherApps.getShortcuts(
        "com.android.shell",
        query,
        targetUser,
    ).list
} finally {
    connection.unbind()
}
```

Set the target app's package in `query`; keep the caller package as `com.android.shell`.

## Define the AIDL contract {#define-aidl-contract}

Enable AIDL in the app module’s `build.gradle.kts`. If your app does not already have AndroidX annotations, add it for `@Keep`.

```kotlin
android {
    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.11.0")
}
```

Save the interface as `src/main/aidl/com/example/app/IMyPrivilegeService.aidl`, using your own package and matching directory. Use transaction code `16777114` for `destroy()`.

```java
package com.example.app;

interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## Implement the service {#implement-service}

Put the Kotlin implementation in the same package. Mark the constructor with `@Keep` so the runtime can instantiate it after minification.

```kotlin
package com.example.app

import androidx.annotation.Keep
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment
import kotlin.system.exitProcess

class MyPrivilegeService @Keep constructor() : IMyPrivilegeService.Stub() {
    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        // Release resources owned by this service.
        if (!PrivilegeUserServiceEnvironment.isEmbedded) {
            exitProcess(0)
        }
    }
}
```

Use a constructor that takes `Context` if your service needs one.

Embedded services must only release their own resources, leaving the shared process running. When destroyed through `stopUserService()`, the runtime exits dedicated child processes, so the explicit exit in this example is optional.

## Use a dedicated process {#dedicated-process}

Set `process = PrivilegeUserServiceProcess.INHERIT` to start or reuse a dedicated process with the server's UID:

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        process = PrivilegeUserServiceProcess.INHERIT,
    )

    val connection = Privilege.bindUserService(spec)
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }
}
```

`serviceClassName` and `tag` together identify an instance. `tag` defaults to `null`; set different tags to create multiple instances of the same service class. Stop an instance before binding again to recreate it.

## Lifecycle and daemon mode {#service-lifecycle}

`daemon` controls whether the whole instance stays alive without connections. It defaults to `false`:

| Operation | Behavior |
| --- | --- |
| `bindUserService(spec, daemon = false)` | Creates or reuses an instance and connects to it, setting non-daemon mode; destroys it after the last connection closes or the owner app process dies |
| `bindUserService(spec, daemon = true)` | Creates or reuses an instance and connects to it, setting daemon mode; keeps it after unbinding or owner app process death |
| `peekUserService(spec)` | Connects only to an existing instance with a matching `process`, preserving its lifecycle policy |
| `connection.unbind()` | Releases this connection; destroys a non-daemon instance after its last connection closes |
| `stopUserService(spec)` | Stops the entire instance identified by class name and tag, invalidating all connections |

All UserServices end when the privileged server exits. App process death clears its connections; use `peekUserService()` to reconnect to a surviving daemon instance after restart.

```kotlin
// Keep the service running after unbinding.
val connection = Privilege.bindUserService(spec, daemon = true)
connection.unbind()

// Stop the service, including any remaining connections.
Privilege.stopUserService(spec)
```

Binding again with the same identity and `process` updates `daemon` without recreating the instance. Omitting the argument also disables daemon mode; use `peekUserService()` to preserve it. Explicitly unbind each connection when finished; dropping the object does not unbind it.

`stopUserService()` closes call entry points before returning, without waiting for disconnect notifications, process exit, or calls in progress. Stopping a missing instance or unbinding again has no effect.

## Observe connection state {#connection-state}

`connection.isConnected` is a `StateFlow<Boolean>`. It becomes `false` after unbind, service stop/replacement, or process death, marking this connection as invalid.

```kotlin
import kotlinx.coroutines.flow.first

val connection = Privilege.bindUserService(spec)
lifecycleScope.launch {
    connection.isConnected.first { connected -> !connected }
    // Update the UI or release local resources associated with this connection.
}
```

Disconnect notifications arrive asynchronously. A disconnected connection cannot recover; use bind or peek to obtain a new one. Business calls and forwarding through invalid connections use the same [failure handling](./binder#failure-semantics).

## Connect only to an existing instance {#peek-existing-service}

`peekUserService()` looks up an instance by `serviceClassName + tag` and requires the same `process` enum value. It returns `null` if none matches, without creating or replacing an instance or changing `daemon`.

```kotlin
val connection = Privilege.peekUserService(spec)
if (connection != null) {
    try {
        val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
        service.getUid()
    } finally {
        connection.unbind()
    }
}
```

A disconnected server raises `PrivilegeUnavailableException`; `null` only means no matching instance was found.

## Use an embedded service {#embedded-service}

Omit `process` to use the default embedded mode:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
)
```

## Detect the execution environment {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` is `true` in embedded mode and `false` in a dedicated child process. See the [`destroy()` example](#implement-service).
