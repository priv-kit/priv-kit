# Priv Kit

`Priv Kit`（仓库名 `priv-kit`）是一个面向 Android 单应用的自管理 Privileged Runtime。

这个项目只解决一件很窄的事：帮助一个应用启动、连接并使用自己的 Privileged Server 进程。项目提供运行时生命周期、Binder 接入、UserService 支持和多种启动策略，不提供 Android 系统 API 兼容层，也不提供高级系统能力封装。

## 项目状态

当前仓库已进入源码实现阶段。Phase 1 已提供 Root、ADB、Shell Start Command 的 Privileged Server 启动、provider-permission-gated Binder handoff、全局 server-binder 状态和 owner-death follow 闭环。

当前 Binder 阶段提供显式目标 Binder 的 remote transact 原语、显式系统服务名的 raw Binder transact 桥和统一 server 不可用失败语义。公开 Binder 原语位于 `:priv-core` 的 `priv.kit.core.binder` package 分区；内部 AIDL 和 server transaction 实现位于 `priv.kit.core.internal.*`。server 通道不可用暴露为 `PrivilegeServerUnavailableException`；目标 Binder 调用失败按 raw Binder 语义透传给调用方处理。它不提供 Binder endpoint 注册槽位，不提供 Android 系统服务类型化代理，也不扩展成高级系统能力封装。

当前 UserService 阶段提供多实例 Binder 管线。公开 spec/exception 位于 `:priv-core` 的 `priv.kit.core.userservice` package 分区；内部 AIDL、wire contract、handshake registry、registry、manager、loader、host、destroyer 和独立 UserService 进程入口位于 `priv.kit.core.internal.userservice`。每个实例由 `serviceClassName + tag` 标识，`version` 只用于控制同一实例是否复用或替换；默认运行在独立 `app_process` 子进程中，低风险服务可以显式选择嵌入 Privileged Server 进程。UserService 可以声明无参构造器，也可以声明 `Context` 构造器；独立进程会优先尝试基于应用 `Application` 初始化，失败后回退 package `Context`，嵌入式只使用 package `Context`，不调用 `makeApplication`。独立进程 UserService 应在自己的 `destroy()` 完成清理并主动 `System.exit()`；嵌入式 UserService 的 `destroy()` 可不实现，且不能调用 `System.exit()`。项目只返回应用自定义 AIDL 的 Binder，不理解也不封装业务接口。

项目边界以 [project-constitution.md](project-constitution.md) 为准。后续所有设计和实现都必须遵守该文档。

## API 承诺

发布到 Maven Central 的模块不自动构成接入应用 API。当前只承诺接入应用引用 `priv-core` 或 `priv-ui` 后可见的编译期 API。

`priv-shared` 可以作为窄 Android 实现 artifact 发布，`priv-adb-crypto` 可以作为独立 JVM artifact 发布，但两者默认只服务于实现复用。`priv.kit.shared` 与 `priv.kit.core.internal.*` 下的类型不属于公开 API，即使其中部分符号因跨模块编译或 Android/JVM 反射要求在字节码层可见。

## 推荐接入路径

普通接入方优先只理解四件事：

1. 启动或连接自己的 Privileged Server。
2. 观察当前运行时状态。
3. 通过 raw Binder 原语或 UserService 承载应用自定义能力。
4. 在不需要时停止或断开运行时。

推荐入口集中在 `Privilege`：

```kotlin
Privilege.startAdb()  // suspend
Privilege.startRoot() // suspend
Privilege.serverState.collect { serverInfo -> ... }
val shellCommand = Privilege.createShellStartCommand()

val connection = Privilege.bindUserService(spec)
val binder = PrivilegeBinderWrapper.fromBinder(targetBinder)

Privilege.shutdownServer()
```

手动 shell、外部启动入口、ADB pairing/TCP 细节、raw Binder transact、owner-death reconnect 和 handshake/launch command 协议都属于高级或内部路径。接入应用不应该在第一步就依赖这些对象，除非正在实现自定义启动、诊断或底层 Binder 验证。

## 命名规范

项目身份：

- GitHub Organization：`priv-kit`
- GitHub Repository：`priv-kit`
- 项目对外名称：`Priv Kit`

Maven 坐标：

- `groupId`：`io.github.priv-kit`
- `artifactId`：`priv-shared`、`priv-core`、`priv-adb-crypto`、`priv-ui`

示例：

```kotlin
implementation("io.github.priv-kit:priv-core:1.0.0")
```

Gradle 模块必须使用 `priv-*` 命名：

- `:priv-shared`
- `:priv-core`
- `:priv-adb-crypto`
- `:priv-ui`
- `:priv-sample`

内部编译期 hidden framework stub 模块为 `:hidden-api`，不作为发布 artifact。

除 `:hidden-api` 中的 framework mirror/stub 外，Kotlin package 必须统一使用 `priv.kit.*`。`:priv-core` 承载公开的 `priv.kit.core`、`priv.kit.core.binder`、`priv.kit.core.userservice`、`priv.kit.core.adb`，以及 `priv.kit.core.internal.*`；`:priv-shared` 只承载 `priv.kit.shared`。

允许的 package 分区包括：

- `priv.kit.core`
- `priv.kit.core.binder`
- `priv.kit.core.userservice`
- `priv.kit.core.adb`
- `priv.kit.core.internal.*`
- `priv.kit.shared`
- `priv.kit.adb.crypto.certificate`
- `priv.kit.adb.crypto.pairing`
- `priv.kit.ui`

禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*` 作为源码 package。

公开 API 必须使用完整单词 `Privilege` 或 `Privilege*` 命名，例如 `Privilege`、`PrivilegeKit`、`PrivilegeServer`、`PrivilegeBinder`、`PrivilegeUserService` 和 `PrivilegeConnection`。

禁止公开 API 使用 `Priv*` 缩写，例如 `PrivKit`、`PrivSession`、`PrivMode`、`PrivServer`、`PrivBinder` 和 `PrivUserService`。

## 目标

- 启动 Privileged Server。
- 连接 Privileged Server。
- 提供 Binder 能力。
- 提供 UserService 能力。
- 支持 Root 启动。
- 支持 ADB 启动。
- 支持手动命令和具备代码执行能力的外部启动入口，并提供通用 bridge runner/host、外部特权进程执行 helper 与主进程实时日志接收 helper；第三方绑定与应用 AIDL 保持在接入侧。
- 让单个应用可以管理自己的特权进程运行时。

## 非目标

本项目不是 Android 系统 API 兼容层，也不能发展成兼容层。

本项目不提供以下高级封装：

- ActivityManager
- PackageManager 领域 facade
- InputManager
- Settings
- AppOps
- 其他高级 Android 系统能力

少量高频 framework 方法可以作为显式参数的透传桥提供，例如权限检查和运行时权限授予；这类 API 不应发展出策略、发现、批处理或领域模型。

以下风格的 API 明确不属于本项目范围：

- `Privilege.input.tap(...)`
- `Privilege.package.install(...)`
- `Privilege.settings.put(...)`
- `Privilege.appops.setMode(...)`

需要这些能力的接入方，应基于本项目提供的 Binder 或 UserService 原语自行实现。

## 模块

当前模块：

- `:priv-shared`
- `:priv-core`
- `:priv-adb-crypto`
- `:priv-ui`
- `:priv-sample`

各模块职责和依赖规则见 [modules.md](modules.md)。

## 架构

项目整体分为几层：

- `:priv-shared`，负责 runtime/UI 真正共用的无领域状态 Android/JDK 底层机制和不变量，只作为内部实现依赖；
- `:priv-core`，负责选择启动策略、连接服务端、承载 Privileged Server 入口、Binder/UserService 原语、ADB/Root/manual/external 启动和内部协议；
- `:priv-adb-crypto`，负责 ADB pairing/certificate 所需的 JVM crypto 实现；
- 可选 Compose UI 帮助层和示例代码，用来展示运行时状态和启动流程，不扩展核心范围。

完整设计见 [architecture.md](architecture.md)。

## 语言和构建约束

- 除 hidden-api stub、framework mirror class、AIDL 兼容桥接外，所有手写源码都必须使用 Kotlin。
- 所有 Gradle 构建脚本都必须使用 Kotlin DSL，也就是 `.gradle.kts`。
- 普通业务模块不得新增 Java 源码。

## 开发规则

新增任何源码实现之前，必须先对照 [project-constitution.md](project-constitution.md)。如果某个设计需要高级 Android API 封装，它属于接入应用，不属于本项目。
