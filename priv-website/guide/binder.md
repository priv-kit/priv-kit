---
description: Use explicit Binder primitives through an app-owned Priv Kit server.
---

# Binder {#binder}

Call Android system services through the privileged server. Your app supplies the service interfaces.

## Access a system service {#system-service}

`fromSystemService()` and `hasSystemService()` look up services by name in the app process by default (`CURRENT_PROCESS`). Binder transactions are forwarded through the privileged server by default. Use the matching Android interface declaration in your app.

```kotlin
val server = Privilege.getServerInfo()
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService("activity"))
val activityManager = IActivityManager.Stub.asInterface(binder)
val granted = activityManager.checkPermission(
    android.Manifest.permission.WRITE_SECURE_SETTINGS,
    server.pid,
    server.uid,
) == android.content.pm.PackageManager.PERMISSION_GRANTED
```

## When to use `REMOTE_PROCESS` {#remote-process}

Use `REMOTE_PROCESS` when the app cannot find a service by name but the privileged server can. With `userService = connection`, lookup runs in that UserService process. One example is `miui.mqsas.IMQSNative` on some Xiaomi ROMs.

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
)
```

To compare lookup results on your device, connect the sample app to its privileged server, open **Test Binder**, enter the service name, and tap **Check Both Processes**.

### Read `meminfo` {#dump-meminfo}

Under the default AOSP Android 16 policy, ordinary app processes cannot obtain the `meminfo` Binder. With the privileged server connected, call `dump()` with `-s` and a package name to read that app’s memory summary.

```kotlin
import android.os.ParcelFileDescriptor
import java.util.concurrent.FutureTask
import priv.kit.core.binder.PrivilegeBinderWrapper
import priv.kit.core.binder.PrivilegeSystemServiceSource

fun readMeminfo(packageName: String): String {
    val binder = checkNotNull(
        PrivilegeBinderWrapper.fromSystemService(
            serviceName = "meminfo",
            source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
        ),
    )
    val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()

    return ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
        val output = FutureTask { input.bufferedReader().use { it.readText() } }
        writeEnd.use { writer ->
            Thread(output, "meminfo-reader").start()
            binder.dump(writer.fileDescriptor, arrayOf("-s", packageName))
        }
        output.get()
    }
}
```

## Forward through a UserService {#user-service}

Pass `userService = connection` to forward calls through a bound [UserService](./user-service#binder-forwarding). Use `connection.callingPackageName` when the system API expects a caller package. Unbinding invalidates the wrapper. Wrap any returned Binder explicitly if its calls also need forwarding.

## Bind resources to the server lifetime {#server-lifecycle}

Use `Privilege.getServerInfo().lifecycleBinder` when a remote resource belongs to the current server. Refresh the token when `Privilege.serverState` changes.

```kotlin
val serverInfo = Privilege.getServerInfo()
val serverLifecycle = serverInfo.lifecycleBinder
serverLifecycle.linkToDeath(
    { Log.d("server", "Privileged Server exited") },
    0,
)
```

## Understand failures {#failure-semantics}

`PrivilegeServerUnavailableException` means there is no usable server connection. `DeadObjectException` can also mean a forwarded target died. Use `PrivilegeBinderCall.orElse(...)` for a safe fallback. A lost reply can hide a completed operation, so retry mutating calls only when safe.
