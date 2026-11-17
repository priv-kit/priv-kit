---
description: Start with Privilege UI, or use priv-core to build a custom authorization interface.
---

# Startup methods {#startup-methods}

If you’re using an app, see the [startup tutorial](./startup-tutorial). This guide is for Android developers integrating the privileged server.

## Start with Privilege UI {#privilege-ui}

Use [Privilege UI](./priv-ui) for a Compose startup page with pairing and permission prompts.

## Build a custom interface with priv-core {#priv-core-custom-interface}

Use the APIs below when your app handles authorization, pairing, and startup errors.

### Observe the connection state {#connection-state}

`Privilege.serverState` is a `StateFlow<PrivilegeServerInfo?>`. A non-null value means the server is connected. Reacquire server-bound resources when the connection changes.

#### Application-wide observation {#application-observation}

Collect from an application-owned scope for work that must continue outside a screen.

```kotlin
appScope.launch {
    Privilege.serverState.collect { serverInfo ->
        updatePrivilegedFeatures(serverInfo)
    }
}
```

#### Screen-local rendering {#screen-state}

In Compose, use lifecycle-aware collection.

```kotlin
val serverInfo by Privilege.serverState.collectAsStateWithLifecycle()
```

### Coordinate an owner-process restart {#owner-process-restart}

Schedule the app restart first, then notify the connected server before exiting. After the owner exits, the server waits up to the requested time for the app to return before resuming active reconnect.

```kotlin
scheduleApplicationRestart()
Privilege.prepareOwnerRestart(
    passiveReconnectTimeoutMillis = 10_000,
)
Process.killProcess(Process.myPid())
```

### Inspect denied server permissions {#denied-server-permissions}

In the built-in UI, use **View restricted permissions**. In a custom interface, query the connected server.

```kotlin
val deniedPermissions = withContext(Dispatchers.IO) {
    Privilege.getDeniedServerPermissions()
}
```

This checks denied manifest permissions. AppOps, SELinux, and individual services can still restrict access. Use `Privilege.checkServerPermission(permission)` to check one permission.

#### Redmi K40 example {#denied-permissions-device-example}

On a Redmi K40 running Android 13 / HyperOS 1.0, the query returned the following 14 denied permissions for Shell with **USB debugging (Security settings)** off.

```text
android.permission.CALL_PHONE
android.permission.CLEAR_APP_USER_DATA
android.permission.GRANT_RUNTIME_PERMISSIONS
android.permission.INJECT_EVENTS
android.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS
android.permission.MANAGE_DEVICE_ADMINS
android.permission.READ_CONTACTS
android.permission.REVOKE_RUNTIME_PERMISSIONS
android.permission.SEND_SMS
android.permission.SET_PREFERRED_APPLICATIONS
android.permission.UPDATE_APP_OPS_STATS
android.permission.WRITE_CONTACTS
android.permission.WRITE_SECURE_SETTINGS
android.permission.WRITE_SETTINGS
```

With the setting on, the query returned an empty list. Query again after changing the setting. See [device permission settings](./permission-restrictions).

### Root {#root}

On a rooted device, grant the `su` request if prompted.

```kotlin
val serverInfo = Privilege.startRoot()
```

### ADB {#adb}

Use Wireless Debugging for discovery and pairing, or connect to a fixed TCP/IP port.

#### Wireless Debugging {#wireless-debugging}

Requires Android 11 or later. Request `ACCESS_LOCAL_NETWORK` first on Android versions that require it.

##### Pair the application {#pairing}

Open **Developer options > Wireless debugging > Pair device with pairing code**. Pass the six-digit code while the screen is open.

```kotlin
val adbManager = Privilege.createAdbManager()

val pairingResult = adbManager.pair(
    pairingCode = pairingCode,
)
```

Call `adbManager.checkPairing()` to check if the app key is already authorized.

##### Start with Wireless Debugging {#wireless-debugging-start}

After pairing, start the server.

```kotlin
val serverInfo = Privilege.startAdb()
```

Keep Wireless Debugging enabled unless the app already has `WRITE_SECURE_SETTINGS` and uses managed Wireless Debugging. Set `wirelessDebuggingControl = NEVER` to leave the device setting under user control.

#### TCP/IP startup {#tcp-ip}

Use a fixed local port such as `PRIVILEGE_ADB_DEFAULT_TCP_PORT`.

##### Open or restore the static port {#tcp-ip-setup}

To open a static port, switch from an authorized ADB connection. Ask the user to confirm first, since switching can interrupt other ADB sessions.

```kotlin
val tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT
val adbManager = Privilege.createAdbManager()

adbManager.switchToTcp(tcpPort = tcpPort)
```

For a previously configured port, call `prepareTcpForStart(tcpPort)`. If it reports `UNAUTHORIZED`, call `requestTcpAuthorization(tcpPort)` and wait for the user to approve.

##### Start on the static port {#tcp-ip-start}

```kotlin
val serverInfo = Privilege.startAdb(
    options = PrivilegeAdbConnectionOptions(
        port = tcpPort,
    ),
)
```

Use `checkTcpAuthorization(tcpPort)` to check authorization without prompting.

### Manual {#manual}

Show the generated command to the user to run from a computer. First access should run off the main thread.

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}
YourApp.showCommandToUser("adb shell $nativeStarterCommand")
```

Running the command again replaces the server for the same app and Android user. See [native library packaging](./getting-started#native-library-packaging).

### External {#external}

Run the coordinator’s command through an authorized root, system, or shell process. For a custom authorizer, use `PrivilegeExternalStartup.runThroughBridge()` and `PrivilegeExternalStartupHost`.

#### Shizuku {#shizuku-v13}

For the built-in page, follow [Add Shizuku](./priv-ui#shizuku). Shizuku API v13 or later is required. When migrating from the official client, remove its Manifest provider and `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider` dependencies.

For a custom interface, add `priv-external-shizuku` at the same version as Core. Use `PrivilegeExternalShizuku.status()` to check access, request authorization on user action, and pass the coordinator’s command to `PrivilegeExternalShizuku.runStarter()`.

## Crash logs {#crash-logs}

Set the output directories in your `Application` before starting the server.

```kotlin
val internalCrashDir = File(context.filesDir, "privilege-crashes").apply { mkdirs() }
PrivilegeConfig.crashLogDirectories = listOfNotNull(
    internalCrashDir,
    context.getExternalFilesDir("privilege-crashes"),
)
```

If those directories are unwritable, the server tries `/data/local/tmp/priv-kit/<appId>/<userId>/crashes/`. Use `Privilege.listFallbackCrashLogFiles()` on a worker thread to find fallback reports, including while disconnected. The app handles storage access and cleanup.

To decode a report, add `kotlinx-serialization-json` and read it as `PrivilegeCrashLog`.

```kotlin
val json = Json { ignoreUnknownKeys = true }
val report = json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
if (report.schemaVersion == 1) {
    println(report.stackTrace)
}
```

Reports cover fatal Java/Kotlin exceptions.
