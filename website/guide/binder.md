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
