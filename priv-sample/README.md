# priv-sample

Sample Android app module for Priv Kit.

Namespace and application id: `priv.kit.sample`.

Phase 1 contents:

- A Compose-only sample surface backed by `PrivilegeSampleViewModel` and Navigation 3.
- Three top-level pages: `Test Connection`, `Test Binder`, and `Test UserService`.
- Each page uses a Material 3 `Scaffold` container with a vertically scrolling `Column` content area.
- Connection tests for Root, manual shell, Wireless ADB, TCP mode, and session logs.
- Binder tests for getting and caching an `IUserManager` proxy.
- A Binder remote transact smoke test that calls cached `IUserManager.getUsers()` through a hidden API stub.
- UserService tests for binding and calling separate app-owned AIDL services with `Context` constructors in the default dedicated-process mode and the explicit embedded-in-server mode.
- Display of connection state, uid, pid, launch mode, protocol version, and server version.
- Manual command display and copy support for pasting into `adb shell` on non-root devices.
- Binder death observation through `PrivilegeRuntime`.

The sample demonstrates the Root runtime minimum loop, manual shell verification path, Wireless ADB startup path, one app-owned hidden API smoke test for Binder remote transact, and two app-owned UserService AIDL smoke tests. Both sample UserServices accept `Context`; the embedded UserService omits `destroy()`, while the dedicated UserService exits from `destroy()` with `System.exit(0)`. It does not demonstrate Delegate or reusable Android system service wrappers.
