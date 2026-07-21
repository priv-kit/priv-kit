# priv-sample

Sample Android app module for Priv Kit.

Namespace and application id: `priv.kit.sample`.

Source packages:

- `priv.kit.sample` contains the app entry point, root navigation, and app-wide theme.
- `priv.kit.sample.common` contains the small diagnostic formatter shared by Debug and Startup integrations.
- `priv.kit.sample.home` contains the Home page that opens Privilege UI or Debug Tools.
- `priv.kit.sample.debug` contains the Connection, Binder, and UserService debug pages together with their state, ViewModel, callbacks, runtime actions, lifecycle controller, and hidden-API probes.
- `priv.kit.sample.userservice` contains the app-owned UserService implementations, shared state, and AIDL contracts.
- `priv.kit.sample.startup` contains the `priv-ui` page, configuration, ViewModel, automatic recovery, notification-pairing integration, and the app-owned Shizuku external-start bridge with its AIDL contract.
- Direct `priv.kit.ui.*` imports stay under `priv.kit.sample.startup`.
- Debug listeners and background probes are activated only while the Debug destination remains in the root back stack.

Phase 1 contents:

- A Compose-only sample surface backed by a root-navigation `PrivilegeSampleViewModel`, a feature-scoped `PrivilegeSampleDebugViewModel`, and Navigation 3.
- A Material 3 theme that follows the system light or dark mode, including the embedded `priv-ui` authorization page.
- A Home page with a primary Privilege UI destination and a secondary Debug Tools destination.
- Three pages inside Debug Tools: `Test Authorization`, `Test Binder`, and `Test UserService`.
- Each page uses a Material 3 `Scaffold` container with a vertically scrolling `Column` content area.
- Authorization tests are split into independent Root, manual shell, Shizuku-backed shell start, Wireless ADB, TCP mode, and session-log tabs.
- Binder tests for getting and caching an `IUserManager` proxy.
- A Binder remote transact smoke test that calls cached `IUserManager.getUsers()` through a hidden API stub.
- UserService tests for binding and calling separate app-owned AIDL services with `Context` constructors in the default dedicated-process mode and the explicit embedded-in-server mode.
- Display of connection state, uid, pid, and protocol version.
- Manual command display and copy support for pasting into `adb shell` on non-root devices.
- Binder death observation through `Privilege`.
- A thin Shizuku UserService bridge with one app-owned AIDL: the app keeps Shizuku binding only, the privileged endpoint delegates to runtime `PrivilegeExternalStartupHost`, and the main process delegates pipe/result orchestration to `PrivilegeExternalStartup.runThroughBridge(...)` before waiting for the privileged server handoff.

The sample demonstrates the Root runtime minimum loop, manual shell verification path, Shizuku-backed shell start path, Wireless ADB startup path, one app-owned hidden API smoke test for Binder remote transact, and two app-owned UserService AIDL smoke tests. Both sample UserServices accept `Context`; the embedded UserService omits `destroy()`, while the dedicated UserService exits from `destroy()` with `System.exit(0)`. It does not demonstrate reusable Android system service wrappers.
