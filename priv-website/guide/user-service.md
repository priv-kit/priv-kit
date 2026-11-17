---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService {#user-service}

Run your own AIDL service or forward Binder calls through a privileged process.

## Forward Binder without a custom service {#binder-forwarding}

Omit `serviceClassName` to use built-in Binder forwarding. Supply the target Android interface in your app.

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

`bindUserService()` starts the service if needed and returns a usable connection. Unbind when finished. The same forwarding option works with a custom UserService.

## Select the process UID {#process-uid}

| `PrivilegeUserServiceProcess.uid` | Execution |
| --- | --- |
| `null` (default) | Dedicated process inheriting the server UID |
| `EMBEDDED` (`-1`) | Shared server process |
| `0`, `1000`, `2000` | Dedicated root, system, or shell process |

Changing UID requires a root server. The process keeps the server’s SELinux domain. `connection.uid` shows the actual UID. Use distinct tags for separate instances.

## Fix root package identity failures in getShortcuts {#launcherapps-shortcuts}

If `LauncherApps.getShortcuts` fails with `Calling package name mismatch` through root, bind a forwarding UserService with `uid = 2000` as shown above. Resolve `ILauncherApps` through that connection and pass `checkNotNull(connection.callingPackageName)` as the caller package (`com.android.shell`). Select the target package in the query. Other Android permission checks still apply.

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
import kotlin.system.exitProcess
import priv.kit.core.userservice.PrivilegeUserServiceEnvironment

class MyPrivilegeService @Keep constructor() : IMyPrivilegeService.Stub() {
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

Use a constructor that takes `Context` if your service needs one.

## Use a dedicated process {#dedicated-process}

Bind the spec to start or reuse a dedicated process and obtain its Binder. Call `stopUserService()` when you want to end the instance.

```kotlin
lifecycleScope.launch {
    val spec = PrivilegeUserServiceSpec(
        serviceClassName = MyPrivilegeService::class.java.name,
        tag = "main",
        version = 1,
    )

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

`serviceClassName` and `tag` identify an instance. Change `version` when the old instance must be replaced.

## Use an embedded service {#embedded-service}

Set `process` to `PrivilegeUserServiceProcess.EMBEDDED` to share the privileged server process.

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    process = PrivilegeUserServiceProcess.EMBEDDED,
)
```

## Detect the execution environment {#execution-environment}

Check `PrivilegeUserServiceEnvironment.isEmbedded` in `destroy()`. Embedded services release their own resources. Only a dedicated service should call `exitProcess()`.
