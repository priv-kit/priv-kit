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
- Typed Binder primitive exceptions.
- `PrivilegeBinderWrapper`, a low-level wrapper that executes transactions for an explicit target `IBinder`, a current-process system service Binder, or an explicitly named server-process system service through the connected Privileged Server.
- `IPrivilegeUserServiceManager` and `IPrivilegeUserServiceProcess`, the shared UserService lifecycle and dedicated-process protocols.
- `PrivilegeUserServiceSpec`, id/process-mode/owner-death models, UserService wire contract, handshake registry, transaction constants, and typed UserService exceptions.

App code should normally enter Binder and UserService through `PrivilegeRuntime`:

```kotlin
val binder = PrivilegeBinderWrapper.fromBinder(targetBinder)
val connection = PrivilegeRuntime.bindUserService(spec)
```

This module does not build or execute startup commands, implement startup transports, host UserService instances, load app classes, run server process behavior, expose UI, or provide typed Android system service facades. Server-side UserService registry, manager, loader, process binder, destroyer, and child-process entry point live in `:priv-server`.
