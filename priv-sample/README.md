# priv-sample

Sample Android app module for Priv Kit.

Namespace and application id: `priv.kit.sample`.

Phase 1 contents:

- A single Compose screen with `Start Root Runtime` and a ready-to-copy manual shell command.
- Display of connection state, uid, pid, mode, protocol version, and server version.
- Manual command display and copy support for pasting into `adb shell` on non-root devices.
- Binder death observation through `PrivilegeSession`.

The sample demonstrates the Root runtime minimum loop and a manual shell verification path. It does not demonstrate UserService, Delegate, or Android system service wrappers.
