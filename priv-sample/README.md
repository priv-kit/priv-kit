# priv-sample

Android examples under `priv.kit.sample`: startup, Binder, files, commands, and UserService.

## Build variants

| Flavor | Minimum Android | Native packaging | Release application ID |
| --- | --- | --- | --- |
| `legacy` | 8.0 / API 26 | Extracted libraries | `priv.kit.sample` |
| `api29` | 10 / API 29 | Load from APK | `priv.kit.sample.api29` |

Use `assembleLegacyDebug` or `assembleApi29Debug` for Debug; use `assembleLegacyRelease` and `assembleApi29Release` for minified validation.

## Packages

| Package | Contents |
| --- | --- |
| `home` | Home page |
| `file` | File tests and read-only browser |
| `command` | Output, timeout, and cancellation |
| `debug` | Connection, Binder, UserService, permission, and input tests |
| `userservice` | App AIDL and implementations |
| `startup` | UI config, recovery, notification pairing, and Shizuku |

## Test pages

Startup supports Root, manual commands, Shizuku, Wireless ADB, and TCP. App initialization prepares user 0's `priv.sh` on IO; other users use the full native command.

File tests cover creation, reads, rename, atomic replacement, walking, and recursive deletion. The browser retains visible names when metadata is inaccessible. Command tests use `/system/bin/sh -c` and support streaming or captured output.

Permissions lists denied server manifest permissions with refresh and copy. It excludes AppOps, SELinux, and service-specific rules. Root returns an empty list.

The sample depends on both `priv-input` and `priv-evdev`. Android input tests use a system-service proxy Binder for input commands. Evdev tests use `PrivilegeSampleInputService` for device queries, display matching, and touch gestures. Secondary displays have a Presentation test window with live event reports. Calls run on IO; controllers and service bindings are released after use.

The Shizuku example owns third-party bindings and AIDL. Its endpoint uses `PrivilegeExternalStartupHost`; the main process uses `PrivilegeExternalStartup.runThroughBridge(...)`.

## Crash logs

The browser reads the app's configured directory and, while connected, `/data/local/tmp`. It filters `.json` reports by app/user and validates identity and schema. Invalid, unreadable, unsupported, or over-1-MiB reports appear as error rows. Reports are sorted newest first and are not deleted automatically.

To create a report, bind the embedded service in Test UserService and press Crash Privileged Process. This kills the server through an uncaught exception. Reconnect before reading fallback files.

See the [guides](../priv-website/guide/getting-started.md) for API usage.
