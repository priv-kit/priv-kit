# Priv Kit

`Priv Kit` 是一个面向 Android 单应用的自管理 Privileged Runtime。它帮助应用启动、连接并管理自己的 Privileged Server 进程，再通过 Binder 或 UserService 承载应用自定义的特权逻辑。

它不是 Android 系统 API 兼容层，也不提供包管理、输入注入、Settings、AppOps、ActivityManager 等高级系统能力封装。需要这些能力时，接入应用可以基于 `Priv Kit` 提供的 Binder 或 UserService 原语自行实现。

## 适用场景

- 应用需要在 Root、ADB、手动 shell 或外部授权工具下启动自己的特权进程。
- 应用已经有自己的 AIDL 或 Binder 协议，只缺少稳定的特权进程启动、连接、死亡监听和重连基础设施。
- 应用希望把特权逻辑放进自己的 UserService，由运行时负责启动、绑定、销毁和状态观察。
- 应用需要支持 Wireless Debugging / TCP ADB、复制命令到 `adb shell`、或交给 Shizuku / Dhizuku 这类外部工具代执行启动命令。
- 应用想保留业务能力的完全控制权，不希望引入一个会替它定义系统操作 API 的通用特权库。

## 功能特性

- **运行时生命周期**：通过 `PrivilegeRuntime` 启动、连接、关闭和观察 Privileged Server。
- **多种启动入口**：支持 Root、ADB、Manual Shell 和 External Start Command。
- **安全 handoff**：服务端通过 app 侧 handshake provider 回传 Binder，provider 使用 `android.permission.INTERACT_ACROSS_USERS_FULL` 和 owner token 做入口收窄。
- **Binder 原语**：支持注册、查找和注销应用 Binder endpoint，支持 death 观察和显式目标 Binder 的 raw transaction 转发。
- **UserService 管线**：支持应用自定义 UserService 的 start、bind、unbind、stop、状态轮询和 dedicated process / in-server process 两种运行模式。
- **可选 Compose UI**：`:priv-ui` 提供运行时状态展示和授权流程控件，接入方也可以完全使用自己的 UI。
- **示例应用**：`:priv-sample` 演示 Root、ADB、Manual Shell、外部启动、Binder 和 UserService 的基本路径。

## 优点

- **边界窄**：项目只负责运行时、启动、Binder 和 UserService，不把系统操作包装成公共 API。
- **接入轻**：通常从 `:priv-runtime` 进入，服务端入口由运行时携带，接入应用不需要自己拼 `app_process` 细节。
- **能力归属清楚**：特权业务逻辑属于接入应用，`Priv Kit` 只提供稳定的承载管线。
- **启动路径统一**：Root、ADB、手动命令和外部授权工具最终走同一条 Binder handoff。
- **失败可处理**：运行时、Binder 和 UserService 都提供类型化状态或异常，接入方不需要靠字符串解析判断失败原因。

## 接入依赖

源码工程内接入：

```kotlin
dependencies {
    implementation(project(":priv-runtime"))
    implementation(project(":priv-ui")) // 可选，只有需要内置 Compose 授权界面时添加
}
```

发布 artifact 接入时使用 Maven 坐标，并将 `<version>` 替换为实际发布版本：

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-runtime:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // 可选
}
```

如果接入方直接使用 ADB 启动能力，Android P+ 上需要在使用前配置 hidden API exemption。具体要求见 [priv-adb/README.md](priv-adb/README.md)。

## 最小启动示例

在后台线程启动或连接 Privileged Server：

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import priv.kit.core.PrivilegeServerInfo
import priv.kit.runtime.PrivilegeRuntime

suspend fun startServerWithRoot(): PrivilegeServerInfo =
    withContext(Dispatchers.IO) {
        PrivilegeRuntime.startRoot()
    }

suspend fun startServerWithAdb(): PrivilegeServerInfo =
    withContext(Dispatchers.IO) {
        PrivilegeRuntime.startAdb()
    }
```

需要让用户手动执行命令时，可以生成一条可复制到 `adb shell` 的命令，然后等待同一条 Binder handoff：

```kotlin
val manual = PrivilegeRuntime.prepareManualShell()

showCommandToUser(manual.command.commandLine)

val serverInfo = manual.awaitServer()
```

需要交给外部授权工具代执行时，使用 External Start Command：

```kotlin
val external = PrivilegeRuntime.prepareExternalStart()

externalStarter.runCommand(external.command.commandLine)

val serverInfo = external.awaitServer()
```

## UserService 示例

应用定义自己的 AIDL，例如 `IMyPrivilegeService.aidl`：

```aidl
package com.example.app;

interface IMyPrivilegeService {
    void destroy() = 16777114;
    String readPrivilegedState() = 1;
}
```

实现自己的 UserService。Release 构建中，可能被反射调用的构造器需要保留：

```kotlin
import android.content.Context
import androidx.annotation.Keep

class MyPrivilegeService private constructor(
    private val context: Context?,
) : IMyPrivilegeService.Stub() {
    @Keep
    constructor() : this(context = null)

    @Keep
    constructor(context: Context) : this(context = context)

    override fun readPrivilegedState(): String {
        return "uid=${android.os.Process.myUid()}"
    }

    override fun destroy() {
        kotlin.system.exitProcess(0)
    }
}
```

启动并绑定这个服务：

```kotlin
import priv.kit.runtime.PrivilegeRuntime
import priv.kit.userservice.PrivilegeUserServiceSpec

val spec = PrivilegeUserServiceSpec(
    serviceClassName = MyPrivilegeService::class.java.name,
    tag = "main",
    version = 1,
)

PrivilegeRuntime.startUserService(spec)

PrivilegeRuntime.bindUserService(spec).use { connection ->
    val service = connection.requireInterface { binder ->
        IMyPrivilegeService.Stub.asInterface(binder)
    }
    val state = service.readPrivilegedState()
}
```

## Binder endpoint 示例

如果应用已经有自己的 Binder 对象，也可以直接注册到当前 Privileged Server：

```kotlin
val registration = PrivilegeRuntime.registerBinderEndpoint(myBinder)

try {
    val endpoint = PrivilegeRuntime.requireBinderEndpoint()
    // 将 endpoint 交给应用自己的协议使用
} finally {
    registration.close()
}
```

## 文档

- [详细项目说明](docs/README.md)
- [项目宪章](docs/project-constitution.md)
- [架构设计](docs/architecture.md)
- [模块说明](docs/modules.md)
- [第三方声明](docs/third-party-notices.md)

所有新能力、公开 API 和示例都必须遵守 [项目宪章](docs/project-constitution.md)。如果某个能力更像包管理、输入、设置、app-ops 或 activity 管理，它应留在接入应用或下游库中，而不是进入 `Priv Kit` 本身。
