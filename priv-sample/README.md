# priv-sample

Sample Android app module for Priv Kit.

Namespace and application id: `priv.kit.sample`.

Phase 1 contents:

- A Compose-only sample surface backed by `PrivilegeSampleViewModel` and Navigation 3.
- Two top-level pages: `Test Connection` and `Test Binder`.
- Each page uses a Material 3 `Scaffold` container with a vertically scrolling `Column` content area.
- Connection tests for Root, manual shell, Wireless ADB, TCP mode, and session logs.
- Binder tests for getting and caching an `IUserManager` proxy.
- A Binder remote transact smoke test that calls cached `IUserManager.getUsers()` through a hidden API stub.
- Display of connection state, uid, pid, launch mode, protocol version, and server version.
- Manual command display and copy support for pasting into `adb shell` on non-root devices.
- Binder death observation through `PrivilegeRuntime`.

The sample demonstrates the Root runtime minimum loop, manual shell verification path, Wireless ADB startup path, and one app-owned hidden API smoke test for Binder remote transact. It does not demonstrate UserService, Delegate, or reusable Android system service wrappers.
