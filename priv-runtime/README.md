# priv-runtime

Client-side runtime orchestration module for Priv Kit.

Namespace and package root: `priv.kit.runtime`.

Phase 1 contents:

- `PrivilegeRuntime.startRoot()` for the minimal Root runtime loop.
- `PrivilegeRuntime.startAdb()` for Wireless Debugging / TCP ADB startup, including custom `PrivilegeAdbIdentity`.
- `PrivilegeRuntime.createManualShellCommand()` for generating a token-scoped command that a developer can paste into `adb shell`.
- `PrivilegeRuntime.prepareManualShell()` for callers that still want a command plus a blocking pending-handshake wait.
- `PrivilegeSession`, which stores `serverInfo`, `serverBinder`, and the connected/disconnected state.
- `PrivilegeHandshakeProvider`, the app-side Binder handoff endpoint protected by a random token.

Runtime owns token generation, shared server launch command construction, pending handshakes, protocol validation, session creation, and Binder death handling. Startup strategy modules only execute or transport the launch command.

The runtime module carries `priv-server` as a runtime-only dependency so apps do not need to declare the server module separately. The server module contributes the R8 consumer rule that keeps its `app_process` entry point.

`startRoot()`, `startAdb()`, `createManualShellCommand()`, and `prepareManualShell()` accept `followDeathDelayMillis`. When the app-side owner process dies, the Privileged Server waits for that grace period before exiting. The default is `PrivilegeRuntime.DEFAULT_FOLLOW_DEATH_DELAY_MILLIS` (10 minutes). Use `0` to exit immediately.

By default, owner-death reconnect is passive: the server waits until the app main process is already running again, then sends the Binder handoff. Set `activeReconnectOnOwnerDeath = true` only if the server should actively call the app handshake provider while the app process is dead, which may start the app process.

The latest owner-death configuration is persisted by the runtime and synced to the Privileged Server through every successful handshake. Calling `configureOwnerDeathBehavior()` while connected also pushes the new values to active sessions immediately, so the next owner-process death follows the latest app-side configuration.

If a reconnected server reports a different protocol or server version than the current app runtime, the runtime rejects it and returns a replacement `app_process` command built from the current APK. The server executes that command in-place before exiting, so client and server code come from the same Priv Kit version without repeating the original privilege authorization flow.

Manual Shell only creates a command for the same Binder handoff path. It does not execute `adb`, implement Wireless Debugging, or add an ADB startup strategy.

This module does not expose `getService()`, `checkService()`, UserService, Wireless Debugging, Delegate, UI, Compose, or Android system service wrappers.
