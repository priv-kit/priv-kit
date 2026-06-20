# priv-core

Shared contracts and value types for Priv Kit.

Namespace and package root: `priv.kit.core`.

Phase 1 contents:

- `PrivilegeServerInfo`, protocol constants, Root/Shell launch mode constants, startup errors, and random token generation.
- `PrivilegeServerLaunchCommand`, the shared `app_process` launch command value model used by startup transports.
- The in-process handshake registry used by the app-side runtime to accept only token-matched Binder handoff calls.

The Privileged Server Binder protocol now belongs to `:priv-binder`.

This module does not build or execute startup commands, implement startup transports, UserService behavior, Binder endpoint registry APIs, project Binder AIDL protocols, or Android system service wrappers.
