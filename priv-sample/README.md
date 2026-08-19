# priv-sample

`priv-sample` demonstrates the public Core and UI APIs under the `priv.kit.sample` namespace.

## Build variants

| Flavor | Minimum Android version | Native library packaging | Release application id |
| --- | --- | --- | --- |
| `legacy` | Android 8.0 (API 26) | `useLegacyPackaging = true` | `priv.kit.sample` |
| `api29` | Android 10 (API 29) | AGP modern packaging | `priv.kit.sample.api29` |

Use `assembleLegacyDebug` to exercise the extracted starter and `assembleApi29Debug` to exercise
linker startup from the APK.

## Source layout

- `priv.kit.sample` contains the app entry point, navigation, and theme.
- `priv.kit.sample.home` contains the Home page.
- `priv.kit.sample.file` contains File API tests and the read-only device-file browser.
- `priv.kit.sample.debug` contains Connection, Binder, and UserService diagnostics.
- `priv.kit.sample.userservice` contains app-owned UserService implementations and AIDL.
- `priv.kit.sample.startup` contains Privilege UI setup, automatic recovery, notification pairing,
  and the app-owned Shizuku bridge.

## Covered flows

The sample includes Root, manual shell, Shizuku-backed external startup, Wireless ADB, and static
TCP. Debug pages exercise server state, Binder death, raw system-service transactions, and both
dedicated and embedded UserService modes.

File examples cover creation, streams, metadata, rename, atomic replacement, directory walking,
recursive deletion, and bounded text or hexadecimal previews. The device browser keeps names
visible when enumeration succeeds but metadata access is denied.

The Shizuku example keeps third-party binding and AIDL in the app. Its privileged endpoint delegates
startup execution to `PrivilegeExternalStartupHost`, while the main process uses
`PrivilegeExternalStartup.runThroughBridge(...)` for pipes, completion, and server handoff.
