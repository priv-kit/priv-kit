---
description: 优先使用 Privilege UI，或通过 priv-core 构建自定义授权界面。
---

# 启动方式 {#startup-methods}

所有启动方式都会启动同一个应用自有 Privileged Server，最终都与应用建立同样的
Binder 连接。区别只在 Privileged Server 最初通过哪种方式启动。

## 优先使用 Privilege UI {#privilege-ui}

大多数应用应直接使用 `priv-ui`。嵌入 `PrivilegeScaffold` 后，即可获得 Root、
无线调试、TCP/IP、手动和外部 Provider，以及对应的状态展示、权限请求、配对、
确认和错误提示。接入与配置见 [Privilege UI](./priv-ui)。

下面的 API 只面向需要替换自带授权页面的应用。

## 使用 priv-core 构建自定义界面 {#priv-core-custom-interface}

应用直接使用 `priv-core` 时，权限请求、配对码输入、确认界面、定期检查状态和错误
展示都需要自行实现。`priv-core` 只提供启动和连接 API。

### 监听连接状态 {#connection-state}

`Privilege.serverState` 是进程级的 `StateFlow<PrivilegeServerInfo?>`。
非 `null` 表示 Privileged Server 已连接，`null` 表示已断开。每个新的收集者
都会立即收到当前值。

#### 应用级持续监听 {#application-observation}

连接变化需要在应用进程存活期间持续触发工作时，在 Application 创建的协程
作用域中收集：

```kotlin
val appScope = MainScope()

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            Privilege.serverState.collect { serverInfo ->
                if (serverInfo != null) {
                    // 已连接，初始化应用级特权功能。
                } else {
                    // 已断开，暂停依赖服务端的功能。
                }
            }
        }
    }
}
```

#### 页面级状态展示 {#screen-state}

Compose 页面只需展示当前状态时，使用生命周期感知的收集方式：

```kotlin
val serverInfo by Privilege.serverState.collectAsStateWithLifecycle()
```

### Root {#root}

```kotlin
val serverInfo = Privilege.startRoot()
```

Root 启动会检查可用 `su` 路径，执行共享服务端命令，然后等待服务端建立 Binder
连接。

### ADB {#adb}

ADB 有两种模式：无线调试和 TCP/IP 静态端口。

#### 无线调试 {#wireless-debugging}

无线调试要求 Android 11 或更高版本。Priv Kit 会为应用保存一组 ADB 密钥，设备
授权这组密钥后，应用才能通过无线调试启动 Privileged Server。

##### 配对应用 {#pairing}

让用户打开“开发者选项 > 无线调试 > 使用配对码配对设备”。配对页面保持打开时，
将页面显示的六位配对码传给 `PrivilegeAdbManager.pair()`：

```kotlin
val adbManager = Privilege.createAdbManager()

val pairingResult = adbManager.pair(
    pairingCode = pairingCode,
)
```

`pair()` 默认自动发现无线调试的配对端口。应用已经取得端口时，可以直接传入：

```kotlin
val pairingPort = adbManager.discoverPairingPort()

adbManager.pair(
    pairingCode = pairingCode,
    port = pairingPort,
)
```

使用 `checkPairing()` 检查应用保存的 ADB 密钥是否已经获得授权：

```kotlin
val pairing = adbManager.checkPairing()
if (!pairing.paired) {
    // 启动前展示配对流程。
}
```

需要反复检查状态时，使用 `openPairingCheckSession()` 保持同一条连接，并在停止
检查时关闭会话。

##### 通过无线调试启动 {#wireless-debugging-start}

```kotlin
val serverInfo = Privilege.startAdb()
```

配对与启动是两个独立操作，`pair()` 成功后不会自动启动服务端。

如果应用仍然声明并已经持有 `WRITE_SECURE_SETTINGS`，默认值
`PrivilegeAdbWirelessDebuggingControl.IF_AVAILABLE` 可以临时打开无线调试、发现
连接端口，并在启动尝试结束后关闭无线调试。应用不得修改该设置时使用 `NEVER`。
如果无线调试未开启且 Priv Kit 无法代为开启时就应终止启动，使用 `REQUIRE`。

Privileged Server 连接成功后，如果 `WRITE_SECURE_SETTINGS` 仍被声明，且服务端
是 Root 或拥有 `android.permission.GRANT_RUNTIME_PERMISSIONS`，运行时会尝试
向应用授予 `WRITE_SECURE_SETTINGS`。缺少这项能力时，用户需要在发现端口前手动
打开无线调试。

在需要申请本地网络访问权限的 Android 版本上，直接使用 `priv-core` 的应用需要
在配对或启动前请求 `ACCESS_LOCAL_NETWORK`。

#### TCP/IP 启动 {#tcp-ip}

TCP/IP 启动连接固定的本机 ADB 端口，不发现无线调试的动态连接端口。默认静态
端口由 `PRIVILEGE_ADB_DEFAULT_TCP_PORT` 提供。

##### 打开或恢复静态端口 {#tcp-ip-setup}

尚未配置端口时，通过一条已经授权的 ADB 连接切换到 TCP/IP 模式：

```kotlin
val tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT
val adbManager = Privilege.createAdbManager()

adbManager.switchToTcp(tcpPort = tcpPort)
```

`switchToTcp()` 需要一条已经授权的无线调试或现有 TCP 连接，用于执行
`adb tcpip`。打开或重启静态端口会影响其他依赖 ADB 的进程。`priv-core` 不提供
确认界面，应用必须在调用前自行取得用户确认。
已经知道当前 ADB 连接使用的端口时，通过
`options = PrivilegeAdbStartOptions(port = sourcePort)` 直接传入。

之前已经配置静态端口时，启动前先检查并恢复该端口：

```kotlin
val authorization = adbManager.prepareTcpForStart(tcpPort = tcpPort)
```

`prepareTcpForStart()` 会检查端口。如果已保存的端口匹配，但 `adbd` 已停止监听，
且应用能够控制 ADB，Priv Kit 可以恢复核心 ADB 服务。这个操作不会打开
无线调试。

如果结果是 `PrivilegeAdbAuthorizationStatus.UNAUTHORIZED`，请求授权并等待用户
在系统弹窗中确认：

```kotlin
val request = adbManager.requestTcpAuthorization(tcpPort = tcpPort)
check(request.authorized) {
    request.failureMessage ?: "ADB 授权未完成"
}
```

##### 通过静态端口启动 {#tcp-ip-start}

直接传入端口。`port` 非 `null` 时，启动流程不会发现无线调试端口：

```kotlin
val serverInfo = Privilege.startAdb(
    options = PrivilegeAdbStartOptions(
        port = tcpPort,
    ),
)
```

只需检查一次授权状态时使用 `checkTcpAuthorization()`，需要反复检查时使用
`openTcpAuthorizationCheckSession()`。调用 `stopTcp(tcpPort)` 可以让 `adbd`
返回 USB 模式。

### 手动 {#manual}

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}
YourApp.showCommandToUser("adb shell $nativeStarterCommand")
```

`priv-core` 返回设备端命令。在 Android 10 及以上版本，它可以通过系统 linker
直接执行 APK 中的 starter，也可以执行旧式打包解压出的 SO。应用向开发机器展示
命令时，在前面添加 `adb shell`。Starter 只允许 root（UID 0）、system
（UID 1000）或 shell（UID 2000）身份运行。不同 Android 版本的要求见
[native 库打包](./getting-started#native-library-packaging)。首次读取会检查已安装的
APK，因此需要在非主线程解析命令。

Android 10 及以上版本使用现代打包时，实际命令可能如下：

```shell
adb shell /system/bin/linker64 '/data/app/.../base.apk!/lib/arm64-v8a/libprivkitstarter.so'
```

使用旧式打包时，命令如下：

```shell
adb shell /data/app/~~-YKUdRFBwGAwYBVzJRt7pA==/priv.kit.sample.debug-A-2guZlsvRZ-9e6xF-K0kQ==/lib/arm64/libprivkitstarter.so
```

### 外部 {#external}

外部启动是手动启动的扩展。手动需要用户执行 native starter 命令，外部则借助外部
授权器，在它提供的特权进程中执行同一条命令。两种方式最终进入同一条
Privileged Server 启动流程，并与应用建立同样的 Binder 连接。

外部授权器可以是应用提供的任何服务或工具，只要它能以 root、system 或 shell
身份执行该命令。应用负责接入第三方授权器、绑定服务和控制访问权限，`priv-core`
负责把命令传给授权器。下面以 Shizuku UserService 为例。这里假设 Shizuku 已经可用并完成授权，
只说明启动 Priv Kit 需要用到的 UserService 代码。

#### 定义 UserService {#define-user-service}

通过应用定义的 AIDL 接口，把命令、标准输出、错误输出和完成回调传入 Shizuku
UserService：

```java
interface IPrivilegeShizukuStartService {
    void start(
        String commandLine,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        in ResultReceiver resultReceiver
    ) = 1;
    void destroy() = 16777114;
}
```

使用 `PrivilegeExternalStartupHost` 实现 UserService：

```kotlin
class PrivilegeShizukuStartService @Keep constructor() :
    IPrivilegeShizukuStartService.Stub() {
    private val host = PrivilegeExternalStartupHost()

    override fun start(
        commandLine: String,
        stdout: ParcelFileDescriptor,
        stderr: ParcelFileDescriptor,
        resultReceiver: ResultReceiver,
    ) {
        host.start(commandLine, stdout, stderr, resultReceiver)
    }

    override fun destroy() {
        host.close()
        exitProcess(0)
    }
}
```

#### 绑定 UserService 并启动 {#bind-user-service}

为 Shizuku UserService 设置固定的 tag。实现或 AIDL 接口不再兼容时修改
version：

```kotlin
val args = Shizuku.UserServiceArgs(
    ComponentName(
        context.packageName,
        PrivilegeShizukuStartService::class.java.name,
    ),
)
    .daemon(false)
    .tag("priv-kit-external-start")
    .processNameSuffix("priv-kit-shizuku-start")
    .version(1)

Shizuku.bindUserService(args, serviceConnection)
```

`ServiceConnection` 返回 AIDL 接口后，把它的 `start()` 方法传给 Priv Kit：

```kotlin
val nativeStarterCommand = withContext(Dispatchers.IO) {
    Privilege.nativeStarterCommand
}

PrivilegeExternalStartup.runThroughBridge(
    commandLine = nativeStarterCommand,
    bridge = { commandLine, stdout, stderr, resultReceiver ->
        shizukuService.start(commandLine, stdout, stderr, resultReceiver)
    },
)
```

请求结束后通过 `Shizuku.unbindUserService(...)` 关闭连接。完整实现见 sample：
[Starter](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleShizukuExternalStarter.kt)、
[UserService 实现](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleShizukuStartService.kt)
和 [AIDL 接口](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/aidl/priv/kit/sample/startup/IPrivilegeSampleShizukuStartService.aidl)。
