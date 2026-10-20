---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService {#user-service}

UserService runs your code in a privileged process and returns the Binder for your AIDL interface.

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

Set `embedded = true` to run directly inside the Privileged Server, saving process startup and memory overhead by sharing the existing process:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    version = 1,
    embedded = true,
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

Check this before terminating a process: embedded services must only release their own resources and must not call `exitProcess`, which terminates the entire privileged process. Select the mode with `PrivilegeUserServiceSpec.embedded`.
