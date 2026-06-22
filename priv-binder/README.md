# priv-binder

Binder communication primitive module for Priv Kit.

Namespace and package root: `priv.kit.binder`.

Current contents:

- `IPrivilegeServer`, the project-owned Binder protocol for the Privileged Server.
- `PrivilegeBinderEndpoint`, a lightweight wrapper around the app-owned `IBinder` endpoint plus death observation.
- `PrivilegeBinderClient`, the runtime-side helper for registering, looking up, requiring, and unregistering the single endpoint.
- `PrivilegeBinderRegistry`, the server-side in-memory endpoint slot with Binder death cleanup.
- `PrivilegeBinderRegistration`, a closeable registration handle.
- `PrivilegeRemoteBinderWrapper`, a low-level wrapper that executes transactions for an explicit target `IBinder` through the connected Privileged Server. Prefer creating it through `PrivilegeRuntime.createRemoteBinderWrapper()` so every transaction resolves the current server Binder through the global getter.
- `PrivilegeRemoteSystemServiceBinder`, a low-level wrapper that resolves an explicit system service name in the connected Privileged Server and forwards only raw Binder transactions. Prefer creating it through `PrivilegeRuntime.createRemoteSystemServiceBinder()`.
- `PrivilegeBinderException`, the sealed Binder primitive error base type.
- `PrivilegeServerDisconnectedException`, `PrivilegeBinderEndpointDeadException`, `PrivilegeBinderEndpointNotFoundException`, and `PrivilegeBinderRemoteCallException`, typed failures for server death, dead endpoints, missing endpoints, and non-death remote call failures.

`IPrivilegeServer` also exposes the opaque UserService manager Binder used by `:priv-user-service`; the UserService protocol itself stays in that module.

This module does not provide typed Android system service wrappers, `ServiceManager` enumeration helpers, endpoint ids, endpoint enumeration, UserService lifecycle APIs, or package/input/settings/app-ops/activity APIs.
