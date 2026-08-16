---
description: Use explicit Binder primitives through an app-owned Priv Kit server.
---

# Binder {#binder}

Priv Kit connects applications to explicit Binder endpoints and system services
while preserving raw transaction contracts. The application supplies the
framework interface and defines the domain behavior built on top of it.

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

The integrating app owns the framework interface and the meaning of each
transaction.

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

The token exposes no privileged operations or custom transactions. Its Binder
identity remains stable for one server process and changes when the server is
replaced. It belongs to the same server snapshot as the other
`PrivilegeServerInfo` fields. Use the new value after `Privilege.serverState`
changes instead of caching it across connections. `Privilege.getServerInfo()`
raises `PrivilegeServerUnavailableException` when no live server is connected.

## Understand failures {#failure-semantics}

Project-owned control calls normalize a missing or dead server to
`PrivilegeServerUnavailableException`. Raw wrapper calls keep forwarded Binder
failures unchanged, preserving the uncertainty between a target Binder death and
a Privileged Server death for application-specific recovery.

Use `PrivilegeBinderCall.orElse(...)` when the app has an explicit fallback for
a server-related or UserService Binder invocation:

- `PrivilegeBinderCallFailure.ServerUnavailable` means the Privileged Server
  was unavailable.
- `PrivilegeBinderCallFailure.BinderDied` means a directly called endpoint
  died.
- Other exceptions retain their original semantics.

Use fallbacks for recovery paths that remain safe when the remote result is
uncertain. The remote process may have completed a mutating operation before
dying.
