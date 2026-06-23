# priv-user-service

UserService Binder primitive module for Priv Kit.

Namespace and package root: `priv.kit.userservice`.

Current contents:

- `PrivilegeUserServiceSpec`, the app-owned service declaration keyed by `serviceClassName + tag`, with `version` controlling reuse or replacement and `destroyTimeoutMillis` controlling dedicated-process teardown fallback.
- `PrivilegeUserServiceProcessMode`, defaulting to `DEDICATED_PROCESS` with explicit `IN_SERVER_PROCESS` opt-in.
- `PrivilegeUserServiceOwnerDeathPolicy`, defaulting to destroying services when the owner app process dies.
- `IPrivilegeUserServiceManager`, the server-side lifecycle manager protocol.
- `IPrivilegeUserServiceProcess` and `PrivilegeUserServiceMain`, the dedicated `app_process` UserService child-process protocol and entry point.
- Server-facing registry and manager implementations that wire the runtime/server protocol. These are implementation plumbing, not the recommended app entry point.

App code should enter UserService through `PrivilegeRuntime`:

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyService::class.java.name,
)

val connection = PrivilegeRuntime.bindUserService(spec)
val service = IMyService.Stub.asInterface(connection.binder)
```

Use `PrivilegeRuntime.startUserService(...)`, `bindUserService(...)`, `stopUserService(...)`, `getUserServiceStatus(...)`, and `watchUserServiceStatus(...)` instead of constructing client, registry, manager, or protocol objects directly.

The module transports app-defined `IBinder` services. A UserService class must implement `IBinder` or `IInterface`; the usual shape is `class MyService : IMyService.Stub()`. The module does not understand or wrap the app's AIDL interfaces. Callers bind a service and then adapt the returned Binder through their own generated AIDL Stub.

UserService construction supports a no-arg constructor, a single `android.content.Context` constructor, or both. If a `Context` constructor is present, Priv Kit prefers it. If no `Context` constructor is present, Priv Kit keeps the no-arg path and does not create a `Context`. In `DEDICATED_PROCESS`, a `Context` constructor receives an app `Application` when `LoadedApk.makeApplication(true, null)` succeeds, and falls back to package `Context` if that framework path fails. In `IN_SERVER_PROCESS`, a `Context` constructor receives only package `Context`; embedded services never call `makeApplication`. If package `Context` creation fails in embedded mode and the service also has a no-arg constructor, Priv Kit falls back to the no-arg constructor.

Release builds should keep every UserService constructor that Priv Kit may call by reflection. In Kotlin, declare explicit secondary constructors and annotate each one with `androidx.annotation.Keep`:

```kotlin
class MyService private constructor(
    private val context: Context?,
) : IMyService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)
}
```

Build `PrivilegeUserServiceSpec.serviceClassName` from `MyService::class.java.name` instead of a hard-coded source name.

If a UserService needs cleanup, define `void destroy() = 16777114;` in the app-owned AIDL. Once one AIDL method has an explicit id, all methods in that interface need explicit ids. Priv Kit will call the reserved destroy transaction when a UserService instance is removed.

Destroy semantics depend on `processMode`:

- `DEDICATED_PROCESS` is for complex services. The UserService owns full resource release and should call `System.exit(0)` after cleanup if it wants the child process to exit gracefully. Priv Kit waits `destroyTimeoutMillis` after requesting destroy, defaulting to `10_000`, and force-kills the child process if it is still alive. `0` means no grace period. A negative value disables this fallback, so Priv Kit only sends destroy and never force-kills that UserService for destroy timeout.
- `IN_SERVER_PROCESS` is for lightweight services embedded in the Privileged Server process. `destroy()` is optional; if implemented, it should be fast and must not call `System.exit()`, because that would terminate the server process itself.

UserService identity is `serviceClassName + tag`. If the identity and `version` match an existing instance, the instance is reused. If the identity matches but `version` changes, the old instance is destroyed and replaced. Different tags create parallel instances.

This module does not provide built-in privileged operations, Android system service facades, package/input/settings/app-ops/activity APIs, or reusable service templates.
