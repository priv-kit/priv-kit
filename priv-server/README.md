# priv-server

Privileged Server process module for Priv Kit.

Namespace and package root: `priv.kit.server`.

Phase 1 contents:

- `PrivilegeServerMain`, the `app_process` entry point.
- A minimal `IPrivilegeServer` implementation exposing uid, pid, mode, protocol version, and server version.
- Token-checked Binder handoff back to the app-side runtime provider.
- Delayed owner-death follow behavior with passive reconnect during the configured grace period, optional active reconnect, runtime-synced reconnect configuration, and in-place replacement startup when the app runtime rejects a stale server version.

The server entry point does not initialize AndroidX Startup, app ContentProviders, an app `Application`, third-party libraries, UserService, Binder registry, or Android system service wrappers.
