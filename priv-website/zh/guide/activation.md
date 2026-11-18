---
description: 优先使用 Privilege UI，或通过 priv-core 构建自定义授权界面。
---

# 启动方式 {#startup-methods}

如果你正在使用某个应用，请看[启动教程](./startup-tutorial)。本页面向在 Android 应用中接入特权服务的开发者。

## 优先使用 Privilege UI {#privilege-ui}

使用 [Privilege UI](./priv-ui) 接入 Compose 启动页，页面负责启动、配对和权限提示。

## 使用 priv-core 构建自定义界面 {#priv-core-custom-interface}

如果由你的应用负责授权、配对和错误提示，可以直接使用下面的 API。

### 监听连接状态 {#connection-state}

`Privilege.serverState` 是 `StateFlow<PrivilegeServerInfo?>`。非空表示已连接。连接变化时，重新获取绑定到服务端的资源。

#### 应用级持续监听 {#application-observation}

需要在页面退出后继续监听时，从应用级协程作用域收集状态。

```kotlin
appScope.launch {
    Privilege.serverState.collect { serverInfo ->
        updatePrivilegedFeatures(serverInfo)
    }
}
```

#### 页面级状态展示 {#screen-state}

在 Compose 中按页面生命周期收集。

```kotlin
val serverInfo by Privilege.serverState.collectAsStateWithLifecycle()
```

### 协调 owner 进程重启 {#owner-process-restart}

先安排应用重启，再在退出前通知已连接的服务端。宿主退出后，服务端会等待应用返回，等待超时后恢复主动重连。

```kotlin
scheduleApplicationRestart()
Privilege.prepareOwnerRestart(
    passiveReconnectTimeoutMillis = 10_000,
)
Process.killProcess(Process.myPid())
```

### 检查服务端被拒绝的权限 {#denied-server-permissions}

内置界面可点「查看受限权限」。自定义界面可以在连接后查询。

```kotlin
val deniedPermissions = Privilege.getDeniedServerPermissions()
```

结果是被拒绝的 Manifest 权限。AppOps、SELinux 和具体系统服务也可能限制访问。检查单项权限用 `Privilege.checkServerPermission(permission)`。

#### Redmi K40 实测示例 {#denied-permissions-device-example}

在运行 Android 13 / HyperOS 1.0 的 Redmi K40 上，关闭「USB 调试（安全设置）」时，查询返回了 Shell 被拒绝的以下 14 项权限。

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

打开该设置后，查询返回空列表。切换设置后可重新查询。参见[各机型权限设置](./permission-restrictions)。

### Root {#root}

在已获取 root 的设备上启动，如有提示，在 root 管理器中授权。

```kotlin
val serverInfo = Privilege.startRoot()
```

### ADB {#adb}

可以使用无线调试发现和配对，也可以连接固定的 TCP/IP 端口。

#### 无线调试 {#wireless-debugging}

需要 Android 11 或更高版本。系统要求本地网络权限时，先申请 `ACCESS_LOCAL_NETWORK`。

##### 配对应用 {#pairing}

打开「开发者选项 > 无线调试 > 使用配对码配对设备」，在配对页面仍打开时提交六位配对码。

```kotlin
val adbManager = Privilege.createAdbManager()

val pairingResult = adbManager.pair(
    pairingCode = pairingCode,
)
```

用 `adbManager.checkPairing()` 查询应用密钥是否已授权。

##### 通过无线调试启动 {#wireless-debugging-start}

配对成功后启动服务端。

```kotlin
val serverInfo = Privilege.startAdb()
```

应用已持有 `WRITE_SECURE_SETTINGS` 并使用托管无线调试时可以自动切换开关，否则请让用户保持无线调试开启。设置 `wirelessDebuggingControl = NEVER` 可由用户管理开关。

#### TCP/IP 启动 {#tcp-ip}

使用固定本地端口，例如 `PRIVILEGE_ADB_DEFAULT_TCP_PORT`。

##### 打开或恢复静态端口 {#tcp-ip-setup}

打开静态端口需要已有授权的 ADB 连接。切换可能中断其他 ADB 会话，先让用户确认。

```kotlin
val tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT
val adbManager = Privilege.createAdbManager()

adbManager.switchToTcp(tcpPort = tcpPort)
```

端口已配置时，先调用 `prepareTcpForStart(tcpPort)`。如果返回 `UNAUTHORIZED`，调用 `requestTcpAuthorization(tcpPort)` 并等待用户确认。

##### 通过静态端口启动 {#tcp-ip-start}

```kotlin
val serverInfo = Privilege.startAdb(
    options = PrivilegeAdbConnectionOptions(
        port = tcpPort,
    ),
)
```

只想查询授权时，调用 `checkTcpAuthorization(tcpPort)`。

### 手动 {#manual}

生成命令交给用户在电脑上执行。首次读取 `nativeStarterCommand` 时可能会检查应用的 APK 文件。

```kotlin
val nativeStarterCommand = Privilege.nativeStarterCommand
YourApp.showCommandToUser("adb shell $nativeStarterCommand")
```

再次运行命令会替换同一应用和 Android 用户的旧服务端。参见 [native 库打包](./getting-started#native-library-packaging)。

### 外部 {#external}

通过已获授权的 root、system 或 shell 进程执行启动协调器给出的命令。自定义授权器可使用 `PrivilegeExternalStartup.runThroughBridge()` 和 `PrivilegeExternalStartupHost`。

#### Shizuku {#shizuku-v13}

使用内置页面时见[添加 Shizuku](./priv-ui#shizuku)。需要 Shizuku API v13 或更高版本。从官方客户端迁移时，移除原 Manifest Provider 以及 `dev.rikka.shizuku:api` 和 `dev.rikka.shizuku:provider` 依赖。

自定义界面可添加与 Core 同版本的 `priv-external-shizuku`。用 `PrivilegeExternalShizuku.status()` 检查状态，用户操作时申请授权，再把启动协调器给出的命令传给 `PrivilegeExternalShizuku.runStarter()`。

## 崩溃日志 {#crash-logs}

在 `Application` 中设置输出目录，然后启动服务端。

```kotlin
val internalCrashDir = File(context.filesDir, "privilege-crashes").apply { mkdirs() }
PrivilegeConfig.crashLogDirectories = listOfNotNull(
    internalCrashDir,
    context.getExternalFilesDir("privilege-crashes"),
)
```

这些目录无法写入时，服务端会尝试 `/data/local/tmp/priv-kit/<appId>/<userId>/crashes/`。可在后台线程用 `Privilege.listFallbackCrashLogFiles()` 查找回退报告，断线时也能调用。文件访问和清理由应用负责。

添加 `kotlinx-serialization-json` 后，可以把报告读成 `PrivilegeCrashLog`。

```kotlin
val json = Json { ignoreUnknownKeys = true }
val report = json.decodeFromString(PrivilegeCrashLog.serializer(), file.readText())
if (report.schemaVersion == 1) {
    println(report.stackTrace)
}
```

报告记录 Java/Kotlin 未捕获异常。
