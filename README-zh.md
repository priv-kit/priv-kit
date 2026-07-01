# Priv Kit

[README.md](./README.md) | [中文文档](./README-zh.md)

零依赖轻量 Android 库，让 Android 应用自身直接借助 Root/ADB 启动特权进程，并通过 Binder/UserService 调用系统层级 API

本项目旨在解决过于依赖外部授权应用，让开发者的应用内部实现自我提权

激活方式：Root、无线 ADB、手动 shell、外部授权

使用方式：Binder（本地 / 远程）和 UserService（嵌入式 / 独立进程）

## 接入依赖

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-runtime:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // 可选 compose 授权界面
}
```

当前只承诺接入应用引用 `priv-runtime` / `priv-ui` 后可见的编译期 API。`priv-runtime` 已经包含 Binder、UserService、Root、ADB、手动 shell 和外部启动入口所需代码；`priv-adb-crypto` 只是独立 JVM 实现模块，不属于 Android 接入 API。

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

用户复制命令手动执行：

```kotlin
val commandLine = Privilege.createShellStartCommand()
YourApp.showCommandToUser(commandLine)
```

把启动命令交给 Shizuku UserService 或其他能够在兼容特权身份中执行代码的外部启动入口。

库侧提供通用的两端 API：特权进程内调用 `PrivilegeExternalStartup.runInCurrentProcess(...)`，主进程用 `PrivilegeExternalStartup.createReceiver(...)` 接收实时日志；Shizuku 的 UserService 绑定和 AIDL 转发由接入应用自己完成。

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
    processMode = PrivilegeUserServiceProcessMode.DEDICATED_PROCESS,
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
    processMode = PrivilegeUserServiceProcessMode.IN_SERVER_PROCESS,
)
```

注意：停止嵌入式 UserService 时，`destroy` 方法仍然会调用，但内部不应该调用 `exitProcess(0)` ，否则整个 server 进程会因此销毁

## 更多文档

- [详细项目说明](docs/README.md)
- [项目宪章](docs/project-constitution.md)
- [架构设计](docs/architecture.md)
- [模块说明](docs/modules.md)
- [第三方声明](docs/third-party-notices.md)
