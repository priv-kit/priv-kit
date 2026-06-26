# priv-server

Privileged Server process module for Priv Kit.

Namespace and package root: `priv.kit.server`.

Phase 1 contents:

- `PrivilegeServerMain`, the `app_process` entry point.
- An `IPrivilegeServer` implementation exposing shutdown, the UserService manager entry point, and explicit system-service presence checks.
- Token-checked Binder handoff back to the app-side runtime provider, with the initial handoff returning the owner token and startup config.
- Remote transact execution for an explicit target Binder, used by low-level Binder wrapper tests.
- Remote transact execution for an explicit system service name, resolving the Binder from the server process instead of the client app process.
- A UserService manager that can start dedicated UserService child processes by default or embed explicitly opted-in services inside the server process.
- Delayed owner-death follow behavior with passive reconnect during the startup-provided grace period and optional active reconnect.
- A consumer R8 rule that keeps the `PrivilegeServerMain.main(String[])` `app_process` entry point while still allowing method-body optimization.

The server entry point does not initialize AndroidX Startup, an app `Application`, third-party libraries, typed Android system service wrappers, or app business logic outside app-defined UserService instances.
