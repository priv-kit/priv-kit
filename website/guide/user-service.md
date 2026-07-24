---
description: Run app-defined AIDL services in embedded or dedicated privileged processes.
---

# UserService

UserService runs application-owned code under the privileged runtime and
returns the raw Binder for the app's own AIDL interface.

## Define the AIDL contract

Use transaction code `16777114` for the destroy method:

```java
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
}
```

## Implement the service

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

## Use a dedicated process

This is the default. The service runs in a separate `app_process` child:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
)

Privilege.startUserService(spec)

Privilege.bindUserService(spec).use { connection ->
    val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
    service.getUid()
}

Privilege.stopUserService(spec)
```

Each instance is identified by `serviceClassName + tag`. A version value only
controls whether the same instance can be reused or must be replaced.

## Use an embedded service

Set `embedded = true` to run directly inside the Privileged Server:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    embedded = true,
)
```

Embedded mode avoids an extra process and suits small, low-risk work. Its
`destroy()` implementation should clean up only service-owned resources;
calling `exitProcess(0)` would terminate the complete Privileged Server.

## Ownership boundary

Priv Kit manages loading, lifecycle, handshakes, and process cleanup. The app
retains complete ownership of the business methods defined by its AIDL.
