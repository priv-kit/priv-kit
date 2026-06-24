# priv-core

Shared contracts, protocol models, and primitive Binder/UserService surfaces for Priv Kit.

Primary package partitions:

- `priv.kit.core`
- `priv.kit.binder`
- `priv.kit.userservice`

Current contents:

- `PrivilegeServerInfo`, protocol constants, startup errors, and random token generation.
- `PrivilegeServerLaunchCommand`, the shared `app_process` launch command value model used by startup transports.
- `PrivilegeServerHandshakeRegistry`, the in-process registry used by the app-side runtime to accept token-matched Privileged Server Binder handoff calls.
- `IPrivilegeServer`, the project-owned Binder protocol for the Privileged Server.
- `PrivilegeBinderEndpoint`, `PrivilegeBinderClient`, `PrivilegeBinderRegistry`, `PrivilegeBinderRegistration`, and typed Binder primitive exceptions.
- `PrivilegeRemoteBinderWrapper`, a low-level wrapper that executes transactions for an explicit target `IBinder` through the connected Privileged Server.
- `PrivilegeRemoteSystemServiceBinder`, a low-level wrapper that resolves an explicit system service name in the connected Privileged Server and forwards only raw Binder transactions.
- `IPrivilegeUserServiceManager` and `IPrivilegeUserServiceProcess`, the shared UserService lifecycle and dedicated-process protocols.
- `PrivilegeUserServiceSpec`, id/status/state/process-mode/owner-death models, UserService wire contract, handshake registry, transaction constants, and typed UserService exceptions.

App code should normally enter Binder and UserService through `PrivilegeRuntime`:

```kotlin
val registration = PrivilegeRuntime.registerBinderEndpoint(binder)
val connection = PrivilegeRuntime.bindUserService(spec)
```

This module does not build or execute startup commands, implement startup transports, host UserService instances, load app classes, run server process behavior, expose UI, or provide typed Android system service facades. Server-side UserService registry, manager, loader, process binder, destroyer, and child-process entry point live in `:priv-server`.
