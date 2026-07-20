# Priv Kit

[README.md](./README.md) | [中文文档](./README-zh.md)

零依赖轻量 Android 库，让 Android 应用自身直接借助 Root/ADB 启动特权进程，并通过 Binder/UserService 调用系统层级 API

本项目旨在解决过于依赖外部授权应用，让开发者的应用内部实现自我提权

激活方式：Root、无线 ADB、手动 shell、外部授权

使用方式：Binder（本地 / 远程）和 UserService（嵌入式 / 独立进程）

## 接入依赖

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // 可选 Compose 界面与静默重放
}
```

当前只承诺接入应用引用 `priv-core` / `priv-ui` 后可见的编译期 API。`priv-core` 已经包含 Binder、UserService、Root、ADB、手动 shell 和外部启动入口所需代码；`priv-shared` 是 Android 实现依赖，`priv-adb-crypto` 是 JVM 实现依赖，两者均由运行时传递解析，不是直接 Android 接入 API，直接依赖也不获得兼容性承诺。

必须配置 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)

```kotlin
HiddenApiBypass.addHiddenApiExemptions("L")
```

## 激活

Root 设备直接启动：

```kotlin
val serverInfo = Privilege.startRoot()
```

通过 ADB Wireless Debugging 或 ADB TCP 启动：

```kotlin
val serverInfo = Privilege.startAdb()
```

如果接入应用仍然声明并已被授予 `WRITE_SECURE_SETTINGS`，默认 ADB 启动会在需要动态无线调试端口时临时打开 Wireless Debugging，完成启动后关闭它。显式静态 TCP 启动前，`PrivilegeAdbStarter.prepareTcpForStart()` 还可以在持久化端口配置仍存在、但 `adbd` 已停止监听时只写入 `ADB_ENABLED=1` 恢复核心 ADB 服务，不打开无线调试，也不要求 Wi-Fi。Privileged Server 连接成功后，runtime 会在该权限仍被声明且 server 是 root 或拥有 `android.permission.GRANT_RUNTIME_PERMISSIONS` 时尝试为 owner app 补授这个启动权限，让后续启动可以走这些托管 ADB 路径；未声明该权限、未被授予该权限或 server 不具备授权能力时会回落到手动打开无线调试、配对或 TCP 端口路径。该能力只属于启动策略，不提供通用 Settings API；PackageManager 能力只限显式参数的 `checkPermission(...)` 与 `grantRuntimePermission(...)` 透传调用。

阻塞式启动、发现和授权检查会保留线程中断标记并原样抛出 `InterruptedException`。Java 调用方需要处理这个受检异常；协程调用方可在可中断调度器中包装这些 API，以线程中断实现协作取消。

使用 `priv-ui` 时，应在 Application 作用域只构造一份 `PrivilegeUiConfig`，并把同一个实例同时传给前台 ViewModel 与可选的无界面静默重放入口：

```kotlin
val serverInfo = PrivilegeUi.startSilently(
    context = applicationContext,
    config = privilegeUiConfig,
)
```

当前台 UI 管理的启动完成 Binder 连接后，`priv-ui` 会在 `filesDir/.priv-kit/ui-start-method` 中保存一个原始 methodId：`root`、`adb-wireless`、`adb-tcpip` 或 `external:<providerId>`。静默入口获得进程内启动门后会先返回已经连接的 server；尚未连接时只尝试这个精确方式，缺少历史、权限或认证以及启动失败均返回 `null`，不会请求 Android 权限、展示 UI 或回退到其他方式。前台与静默启动互斥：已受理的前台启动副作用会持有可嵌套的前台租约直至完成；静默启动期间，内置 UI 会禁用带副作用的入口，并在静默启动释放后先刷新 runtime 状态再恢复入口。若 Root 管理器中原有授权已经失效，Root 管理器仍可能展示自己的授权界面。各启动方式的详细约束见 [priv-ui 文档](priv-ui/README.md#foreground-and-silent-startup)。

用户复制命令手动执行：

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.showCommandToUser(commandLine)
```

把启动命令交给 Shizuku UserService 或其他能够在兼容特权身份中执行代码的外部启动入口。

runtime 负责通用桥接机制：主进程调用 `PrivilegeExternalStartup.runThroughBridge(...)`，特权端只需把唯一启动方法委托给 `PrivilegeExternalStartupHost`；`ParcelFileDescriptor` 管道、实时日志、完成通知、超时和并发拒绝均由 runtime 处理。`runInCurrentProcess(...)` 与 `createReceiver(...)` 继续作为底层 helper。接入应用只保留 Shizuku UserService 绑定和应用自有 AIDL，并负责限制该 Binder 入口的访问范围。

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.bindUserServiceAndRun(commandLine)
```

服务连接后，可使用 Binder/UserService。

## Binder

当前进程 binder

```kotlin
val activityBinder = PrivilegeBinderWrapper.fromSystemService("activity")
val activityManager = IActivityManager.Stub.asInterface(activityBinder)
Log.d("activity", activityManager.getTasks(1).toString()) // 获取设备前台应用界面
```

某些服务只能在 shell 进程下获取

```kotlin
val binder = PrivilegeBinderWrapper.fromSystemService(
    serviceName = "miui.mqsas.IMQSNative",
    source = PrivilegeSystemServiceSource.SERVER_PROCESS,
)
```

## UserService

自定义 AIDL，使用 `16777114` 标记销毁方法，外部停止用户服务时，此方法会被调用

```aidl
interface IMyPrivilegeService {
    void destroy() = 16777114;
    String getUid() = 1;
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

    override fun getUid(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        exitProcess(0)
    }
}
```

独立进程 UserService：默认模式，服务跑在单独的 `app_process` 子进程里。

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
)
Privilege.startUserService(spec)
Privilege.bindUserService(spec).use { connection ->
    val service = IMyPrivilegeService.Stub.asInterface(connection.binder)
    service.getUid()
}
Privilege.stopUserService(spec)
```

嵌入式 UserService：服务直接跑在 Privileged Server 进程里，不会创建新进程，适合轻量逻辑。

```kotlin
val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "embedded",
    embedded = true,
)
```

注意：停止嵌入式 UserService 时，`destroy` 方法仍然会调用，但内部不应该调用 `exitProcess(0)` ，否则整个 server 进程会因此销毁

## 更多文档

- [详细项目说明](docs/README.md)
- [项目宪章](docs/project-constitution.md)
- [架构设计](docs/architecture.md)
- [模块说明](docs/modules.md)
- [第三方声明](docs/third-party-notices.md)
