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
    source = PrivilegeSystemServiceSource.REMOTE_PROCESS,
)
```

## Forward through a UserService {#user-service}

Pass `userService = connection` to `fromSystemService`, `fromBinder`, or `hasSystemService` to select a bound UserService process. The default `REMOTE_PROCESS` resolves names inside that process; without a connection it uses the main server. `CURRENT_PROCESS` explicitly resolves names in the app while still forwarding transactions through the selected process.

A [built-in UserService](./user-service#binder-forwarding) needs no custom class or AIDL and can request UID 1000 or 2000. Existing custom UserServices support forwarding through their connection without changing their AIDL. `connection.callingPackageName` supplies the corresponding package identity when required by the target API.

For wrappers with a UserService connection, `linkToDeath` observes that process's lifecycle. Unbind invalidates further calls but does not itself produce a process death notification. Returned Binder interfaces are not automatically wrapped. A closed connection throws `DeadObjectException`; the wrapper never falls back to a different process.

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

Core control calls and Binder wrappers use the same `PrivilegeServerUnavailableException` when the privileged server is unavailable. A wrapper without a connection throws it before sending the transaction. Exceptions from the actual transaction propagate unchanged.

Use `PrivilegeBinderCall.orElse(...)` when the app has an explicit fallback for
a server-related or UserService Binder invocation:

- `PrivilegeBinderCallFailure.ServerUnavailable` means the Privileged Server
  was unavailable; its `exception` is a `PrivilegeServerUnavailableException`.
- `PrivilegeBinderCallFailure.BinderDied` preserves a `DeadObjectException`.
  For a forwarded transaction, this exception alone cannot identify whether
  the privileged server or the target endpoint died. Do not mark the server
  disconnected solely from this classification.
- Other exceptions retain their original semantics.

The helper can also wrap suspending calls, command result collection, and UserService
operations. Connection failures propagate directly; the helper does not search nested
causes or retry the operation. Cancellation, permission errors, ordinary I/O failures,
and command timeouts do not invoke the fallback.

Fallback fits recovery paths that remain safe with an uncertain remote result.
The remote process may have completed a mutating operation before dying.
