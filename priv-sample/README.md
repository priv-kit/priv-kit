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
- `priv.kit.sample.command` contains streaming and captured command-execution tests.
- `priv.kit.sample.debug` contains Connection, Binder, and UserService diagnostics.
- `priv.kit.sample.userservice` contains app-owned UserService implementations and AIDL.
- `priv.kit.sample.startup` contains Privilege UI setup, automatic recovery, notification pairing,
  and the app-owned Shizuku bridge.

## Covered flows

The sample includes Root, manual shell, Shizuku-backed external startup, Wireless ADB, and static
TCP. Debug pages exercise server state, Binder death, raw system-service transactions, and both
dedicated and embedded UserService modes.

The horizontally scrollable debug tabs include Test Permissions after Test UserService. Open it to
query denied server permissions, select and copy permission names, or refresh the snapshot.
Connection changes clear the result; the Permissions page reloads for the new server while selected.
Root returns an empty list.
The query covers denied manifest permissions of packages associated with the server UID, not
AppOps, SELinux, or service-specific restrictions; an empty list is not a capability guarantee.

File examples cover creation, streams, metadata, rename, atomic replacement, directory walking,
recursive deletion, and bounded text or hexadecimal previews. The device browser keeps names
visible when enumeration succeeds but metadata access is denied.

The command page starts shell text explicitly through `/system/bin/sh -c`. It exercises the
single-use process handle through both live stdout/stderr events and a bounded final result, and
exposes timeout and cancellation controls.

The Shizuku example keeps third-party binding and AIDL in the app. Its privileged endpoint delegates
startup execution to `PrivilegeExternalStartupHost`, while the main process uses
`PrivilegeExternalStartup.runThroughBridge(...)` for pipes, completion, and server handoff.

## Crash browser

The Home page opens Crash Logs. The page reads the configured app directory without a privileged
connection, then additionally scans `/data/local/tmp` while connected. It filters published `.json`
files by the current application and Android user, accepts short filenames only in the app directory,
validates report identity and schema, and sorts
newest crashes first. Refresh reloads both available sources; opening a row shows selectable
metadata and the complete exception stack. Unreadable, malformed, unsupported, or larger-than-1-MiB
reports remain visible as error rows instead of preventing the other reports from loading.
The sample depends on `kotlinx-serialization-json` to consume Core's public `PrivilegeCrashLog` model.

To produce a report, bind the embedded service in Test UserService and press Crash Privileged
Process. This terminates the privileged server through an uncaught worker-thread exception.
Return Home and open Crash Logs; fallback files require a privileged connection again.
