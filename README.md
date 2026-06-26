# Priv Kit

零依赖轻量 Android 库，让 Android 应用自身直接借助 Root/ADB 启动特权进程，并通过 Binder/UserService 调用系统层级 API

本项目旨在解决过于依赖外部授权应用，让开发者的应用内部实现自我提权

激活方式：Root、无线 ADB、手动 shell，外部授权

使用方式：Binder（本地 / 远程）和 UserService（嵌入式 / 独立进程）

## 接入依赖

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-runtime:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // 可选 compose 授权界面
}
```

配置 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)

```kotlin
HiddenApiBypass.addHiddenApiExemptions("L")
```

## 激活

先观察 Privileged Server 的连接和断开：

```kotlin
val stopListenConnected = PrivilegeRuntime.addServerConnectedListener { serverInfo ->
    YourApp.onServerConnected(serverInfo)
}
val stopListenDisconnected = PrivilegeRuntime.addServerDisconnectedListener {
    YourApp.onServerDisconnected()
}
```

Root 设备直接启动：

```kotlin
val serverInfo = PrivilegeRuntime.startRoot()
```

通过 ADB Wireless Debugging 或 TCP ADB 启动：

```kotlin
val serverInfo = PrivilegeRuntime.startAdb()
```

让用户复制命令到 `adb shell` 手动执行：

```kotlin
val commandLine = PrivilegeRuntime.createShellStartCommand()
YourApp.showCommandToUser(commandLine)
```

把启动命令交给 Shizuku 或其他外部授权工具执行：

```kotlin
val commandLine = PrivilegeRuntime.createShellStartCommand()
YourApp.externalStarter.runCommand(commandLine)
```

Shell Start Command 可以在任意时刻执行。server 启动后会通过同一条 Binder handoff 回传给应用，并由 `addServerConnectedListener()` 接入。

服务连接后，可使用 Binder/UserService。

## Binder

Binder 只提供底层 raw transaction 桥，不提供注册槽位或类型化系统 API。

显式目标 Binder：应用把一个目标 Binder 包成远程 Binder，让 transaction 通过 Privileged Server 执行。

```kotlin
val binder = PrivilegeBinderWrapper.fromBinder(targetBinder)
```

如果目标是当前进程可见的系统服务，可以直接按显式服务名获取 raw Binder。这个入口内部调用 hidden `ServiceManager.getService(name)`，接入应用仍需先配置 HiddenApiBypass：

```kotlin
val activityService = PrivilegeBinderWrapper.fromSystemService("activity")
    ?: error("activity service is not available in the current process")
```

如果目标只在 Privileged Server 进程可见，可以显式选择 server 进程来源。返回值仍然只是按服务名延迟解析的 raw Binder，不会向 app 暴露 server 进程里的真实 Binder：

```kotlin
val serverActivityService = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "activity",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
    ?: error("activity service is not available in the server process")
```

## UserService

自定义 AIDL：

```aidl
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String readState() = 1;
}
```

实现服务：

```kotlin
class MyPrivilegeService private constructor(
    private val context: Context?,
) : IMyPrivilegeService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)

    override fun readState(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }
}
```

独立进程 UserService：默认模式，服务跑在单独的 `app_process` 子进程里。

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
    processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
)

PrivilegeRuntime.startUserService(spec)

PrivilegeRuntime.bindUserService(spec).use { connection ->
    val service = connection.requireInterface {
        IMyPrivilegeService.Stub.asInterface(it)
    }
    service.readState()
}

PrivilegeRuntime.stopUserService(spec)
```

嵌入式 UserService：服务直接跑在 Privileged Server 进程里，适合轻量、短耗时逻辑。

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
)

PrivilegeRuntime.startUserService(spec)
PrivilegeRuntime.stopUserService(spec)
```

## 停止

停止某个 UserService：

```kotlin
PrivilegeRuntime.stopUserService(spec)
```

关闭当前 Privileged Server：

```kotlin
PrivilegeRuntime.shutdownServer()
```

## 更多文档

- [详细项目说明](docs/README.md)
- [项目宪章](docs/project-constitution.md)
- [架构设计](docs/architecture.md)
- [模块说明](docs/modules.md)
- [第三方声明](docs/third-party-notices.md)
