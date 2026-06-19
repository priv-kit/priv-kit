# priv-runtime

Client-side runtime orchestration module for Priv Kit.

Namespace and package root: `priv.kit.runtime`.

Phase 1 contents:

- `PrivilegeRuntime.startRoot()` for the minimal Root runtime loop.
- `PrivilegeRuntime.startAdb()` for Wireless Debugging / TCP ADB startup, including custom `PrivilegeAdbIdentity`.
- `PrivilegeRuntime.prepareManualShell()` for generating a token-scoped command that a developer can paste into `adb shell`.
- `PrivilegeSession`, which stores `serverInfo`, `serverBinder`, and the connected/disconnected state.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by a random token.

Runtime owns token generation, shared server launch command construction, pending handshakes, protocol validation, session creation, and Binder death handling. Startup strategy modules only execute or transport the launch command.

Manual Shell only prepares a command and waits for the same Binder handoff path. It does not execute `adb`, implement Wireless Debugging, or add an ADB startup strategy.

This module does not expose `getService()`, `checkService()`, UserService, Wireless Debugging, Delegate, UI, Compose, or Android system service wrappers.
