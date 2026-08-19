---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService {#user-service}

UserService runs application-owned code under the privileged runtime and
returns the raw Binder for the app's own AIDL interface.

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

## Detect the execution environment {#execution-environment}

`PrivilegeUserServiceEnvironment.isEmbedded` tells code running inside a
UserService which process model owns the current instance. It returns `true`
when the service is embedded in the Privileged Server process and `false` when
the service runs in its own dedicated `app_process` child. The value is stable
for the lifetime of the process and is cached after its first read.

Use it before process-wide actions such as termination or global cleanup. In
the implementation above, a dedicated child exits from `destroy()`, while an
embedded service limits cleanup to its own resources because it shares the
Privileged Server process. The host selects the mode with
`PrivilegeUserServiceSpec.embedded`.

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

`startUserService`, `bindUserService`, and `stopUserService` are suspending,
cancellable operations. Service-lock waits and dedicated-process startup run
outside the caller thread. If the coroutine is cancelled
before an operation is accepted, the runtime removes pending work and disposes
of a process or connection created only for that cancelled operation.
`connection.unbind()` is an idempotent suspending operation. Once invoked, it
runs in a non-cancellable context so mandatory resource cleanup is not abandoned,
while the server performs the work through the same bounded asynchronous protocol
outside Binder threads.

Each instance is identified by `serviceClassName + tag`. The `version` value
expresses whether the runtime can reuse the instance or replaces it. Change the
value when the implementation becomes incompatible.

## Use an embedded service {#embedded-service}

Set `embedded = true` to run directly inside the Privileged Server:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    embedded = true,
)
```

Embedded mode avoids an extra process and suits small, low-risk work.
`destroy()` cleans up service-owned resources and leaves the shared Privileged
Server running. Binding is usually faster because it skips child-process launch
and claim, while construction remains asynchronous and cancellable.
