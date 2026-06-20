# priv-server

Privileged Server process module for Priv Kit.

Namespace and package root: `priv.kit.server`.

Phase 1 contents:

- `PrivilegeServerMain`, the `app_process` entry point.
- An `IPrivilegeServer` implementation exposing uid, pid, launch mode, protocol version, server version, owner-death configuration, shutdown, and Binder endpoint registration.
- Token-checked Binder handoff back to the app-side runtime provider.
- A server-side single Binder endpoint slot that unregisters the endpoint when its Binder owner dies.
- Remote transact execution for an explicit target Binder, used by low-level Binder wrapper tests.
- Delayed owner-death follow behavior with passive reconnect during the configured grace period, optional active reconnect, runtime-synced reconnect configuration, and in-place replacement startup when the app runtime rejects a stale server version.
- A consumer R8 rule that keeps the `PrivilegeServerMain.main(String[])` `app_process` entry point while still allowing method-body optimization.

The server entry point does not initialize AndroidX Startup, app ContentProviders, an app `Application`, third-party libraries, UserService, or Android system service wrappers.
