---
description: Use explicit Binder primitives through an app-owned Priv Kit server.
---

# Binder {#binder}

Call Android system services through the privileged server. Your app supplies the service interfaces.

## Access a system service {#system-service}

Resolve a service by name and use the matching Android interface declaration in your app. The default `REMOTE_PROCESS` resolves it in the privileged process. Use `CURRENT_PROCESS` for lookup in the app. Run synchronous service calls on a worker thread.

```kotlin
val granted = withContext(Dispatchers.IO) {
    val server = Privilege.getServerInfo()
    val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService("activity"))
    val activityManager = IActivityManager.Stub.asInterface(binder)
    activityManager.checkPermission(
        android.Manifest.permission.WRITE_SECURE_SETTINGS,
        server.pid,
        server.uid,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
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
