# priv-core

Android privileged runtime under `priv.kit.core`.

| API | Purpose |
| --- | --- |
| `Privilege.startRoot()` / `startAdb()` | Start the server |
| `Privilege.createAdbManager()` | ADB pairing, authorization, and TCP control |
| `Privilege.serverState` | Observe the connection |
| `Privilege.prepareOwnerRestart(...)` | Notify the server before restarting the app |
| `Privilege.file(path)` | File operations |
| `Privilege.startCommand(...)` | Command execution and output |
| UserService APIs | Start, bind, unbind, and stop app-defined services |
| `PrivilegeBinderWrapper` | Forward Binder transactions |

Each package and Android user has its own server. Multi-process apps choose one process for initialization and startup. Reacquire connection resources after server death; configure reconnect behavior through `PrivilegeConfig`.

## Guides

- [Setup and native packaging](../priv-website/guide/getting-started.md)
- [Startup, reconnect, and crash logs](../priv-website/guide/activation.md)
- [Binder](../priv-website/guide/binder.md), [files](../priv-website/guide/file-proxy.md), [commands](../priv-website/guide/commands.md), [UserService](../priv-website/guide/user-service.md)
- [Internal architecture](../docs/architecture.md)

Use `priv-ui` for the authorization page. The app supplies system-service interfaces, third-party authorization bindings, and business AIDL.
