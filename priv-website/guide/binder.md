---
description: Use explicit Binder primitives through an app-owned Priv Kit server.
---

# Binder {#binder}

Priv Kit forwards Binder transactions through the privileged server. Your app supplies the Android service interfaces.

## Access a system service {#system-service}

Resolve an explicit service name through the connected Privileged Server:

```kotlin
val activityBinder = PrivilegeBinderWrapper.fromSystemService("activity")
val activityManager = IActivityManager.Stub.asInterface(activityBinder)

Log.d(
    "activity",
    activityManager.getTasks(1).toString(),
)
```

Some services must be resolved inside the shell or root server process:

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
```

## Bind resources to the server lifetime {#server-lifecycle}

Some Binder APIs accept an owner or death token so that the remote process can
release a resource when its owner exits. Use the dedicated server lifecycle
Binder when that resource belongs to the current Privileged Server process:

```kotlin
val serverInfo = Privilege.getServerInfo()
val serverLifecycle = serverInfo.lifecycleBinder
serverLifecycle.linkToDeath(
    { Log.d("server", "Privileged Server exited") },
    0,
)
```

This death token remains stable until the server is replaced. Refresh it when `Privilege.serverState` changes. `getServerInfo()` throws `PrivilegeServerUnavailableException` if disconnected.

## Understand failures {#failure-semantics}

Core control calls use `PrivilegeServerUnavailableException` for a missing or dead server. Forwarding without a connection throws `PrivilegeBinderUnavailableException`, a subclass of `DeadObjectException`. Exceptions from the actual transaction propagate unchanged.

Use `PrivilegeBinderCall.orElse(...)` when the app has an explicit fallback for
a server-related or UserService Binder invocation:

- `PrivilegeBinderCallFailure.ServerUnavailable` means the Privileged Server
  was unavailable, covering both unavailable-server exception types above.
- `PrivilegeBinderCallFailure.BinderDied` means a directly called endpoint
  died.
- Other exceptions retain their original semantics.

Fallback fits recovery paths that remain safe with an uncertain remote result.
The remote process may have completed a mutating operation before dying.
