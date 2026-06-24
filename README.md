# Priv Kit

`Priv Kit`（仓库名 `priv-kit`）是一个面向 Android 单应用的自管理 Privileged Runtime。

这个项目只解决一件很窄的事：帮助一个应用启动、连接并使用自己的 Privileged Server 进程。项目提供运行时生命周期、Binder 接入、UserService 支持和多种启动策略，不提供 Android 系统 API 兼容层，也不提供高级系统能力封装。

## 项目状态

当前仓库已进入源码实现阶段。Phase 1 已提供 Root、ADB、Manual Shell 和 External Start Command 的 Privileged Server 启动、token-gated Binder handoff、全局 server-binder 状态和 owner-death follow 闭环。

当前 Binder 阶段提供底层 Binder endpoint 注册、查找、注销、death 观察、显式目标 Binder 的 remote transact 原语、显式系统服务名的 raw Binder transact 桥和类型化失败语义。相关 AIDL、共享模型和 raw primitive 已并入 `:priv-core` 的 `priv.kit.binder` package 分区。server death、endpoint dead、endpoint not found 等错误可通过专门异常类型识别，不需要依赖异常 message。它不提供 Android 系统服务类型化代理，也不扩展成高级系统能力封装。

当前 UserService 阶段提供多实例 Binder 管线。共享 AIDL、spec/status/exception、wire contract 和 handshake registry 位于 `:priv-core` 的 `priv.kit.userservice` package 分区；registry、manager、loader、host、destroyer 和独立 UserService 进程入口属于 `:priv-server`。每个实例由 `serviceClassName + tag` 标识，`version` 只用于控制同一实例是否复用或替换；默认运行在独立 `app_process` 子进程中，低风险服务可以显式选择嵌入 Privileged Server 进程。UserService 可以声明无参构造器，也可以声明 `Context` 构造器；独立进程会优先尝试基于应用 `Application` 初始化，失败后回退 package `Context`，嵌入式只使用 package `Context`，不调用 `makeApplication`。独立进程 UserService 应在自己的 `destroy()` 完成清理并主动 `System.exit()`，server 只做可关闭的超时强杀兜底；嵌入式 UserService 的 `destroy()` 可不实现，且不能调用 `System.exit()`。项目只返回应用自定义 AIDL 的 Binder，不理解也不封装业务接口。

项目边界以 [docs/project-constitution.md](docs/project-constitution.md) 为准。后续所有设计和实现都必须遵守该文档。

## 推荐接入路径

普通接入方优先只理解四件事：

1. 启动或连接自己的 Privileged Server。
2. 观察当前运行时状态。
3. 通过 Binder 或 UserService 暴露应用自定义能力。
4. 在不需要时停止或断开运行时。

推荐入口集中在 `PrivilegeRuntime`：

```kotlin
PrivilegeRuntime.startAdb()
PrivilegeRuntime.startRoot()
val externalStart = PrivilegeRuntime.prepareExternalStart()

val connection = PrivilegeRuntime.bindUserService(spec)
val registration = PrivilegeRuntime.registerBinderEndpoint(binder)

PrivilegeRuntime.shutdownServer()
```

`Manual Shell`、External Start Command、ADB pairing/TCP 细节、raw Binder transact、owner-death reconnect 和 handshake/launch command 协议都属于高级或内部路径。接入应用不应该在第一步就依赖这些对象，除非正在实现自定义授权、诊断或底层 Binder 验证。

## 命名规范

项目身份：

- GitHub Organization：`priv-kit`
- GitHub Repository：`priv-kit`
- 项目对外名称：`Priv Kit`

Maven 坐标：

- `groupId`：`io.github.priv-kit`
- `artifactId`：`priv-core`、`priv-runtime`、`priv-server`、`priv-adb-crypto`、`priv-adb`、`priv-ui`

示例：

```kotlin
implementation("io.github.priv-kit:priv-runtime:1.0.0")
```

Gradle 模块必须使用 `priv-*` 命名：

- `:priv-core`
- `:priv-runtime`
- `:priv-server`
- `:priv-adb-crypto`
- `:priv-adb`
- `:priv-ui`
- `:priv-sample`

内部编译期 hidden framework stub 模块为 `:hidden-api`，不作为发布 artifact。

除 `:hidden-api` 中的 framework mirror/stub 外，Kotlin package 必须统一使用 `priv.kit.*`。`:priv-core` 承载 `priv.kit.core`、`priv.kit.binder` 和共享 `priv.kit.userservice` 协议；`:priv-server` 承载 `priv.kit.server` 和服务端侧 `priv.kit.userservice` 实现。

允许的 package 分区包括：

- `priv.kit.core`
- `priv.kit.runtime`
- `priv.kit.server`
- `priv.kit.binder`
- `priv.kit.userservice`
- `priv.kit.adb.crypto.certificate`
- `priv.kit.adb.crypto.pairing`
- `priv.kit.adb`
- `priv.kit.ui`

禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*` 作为源码 package。

公开 API 必须使用完整单词 `Privilege*` 命名，例如 `PrivilegeKit`、`PrivilegeLaunchMode`、`PrivilegeServer`、`PrivilegeBinder`、`PrivilegeUserService`、`PrivilegeRuntime` 和 `PrivilegeConnection`。

禁止公开 API 使用 `Priv*` 缩写，例如 `PrivKit`、`PrivSession`、`PrivMode`、`PrivServer`、`PrivBinder` 和 `PrivUserService`。

## 目标

- 启动 Privileged Server。
- 连接 Privileged Server。
- 提供 Binder 能力。
- 提供 UserService 能力。
- 支持 Root 启动。
- 支持 ADB 启动。
- 支持手动命令和外部授权工具代执行启动命令。
- 让单个应用可以管理自己的特权进程运行时。

## 非目标

本项目不是 Android 系统 API 兼容层，也不能发展成兼容层。

本项目不提供以下封装：

- ActivityManager
- PackageManager
- InputManager
- Settings
- AppOps
- 其他高级 Android 系统能力

以下风格的 API 明确不属于本项目范围：

- `PrivilegeRuntime.input.tap(...)`
- `PrivilegeRuntime.package.install(...)`
- `PrivilegeRuntime.settings.put(...)`
- `PrivilegeRuntime.appops.setMode(...)`

需要这些能力的接入方，应基于本项目提供的 Binder 或 UserService 原语自行实现。

## 模块

计划模块：

- `:priv-core`
- `:priv-runtime`
- `:priv-server`
- `:priv-adb-crypto`
- `:priv-adb`
- `:priv-ui`
- `:priv-sample`

各模块职责和依赖规则见 [docs/modules.md](docs/modules.md)。

## 架构

项目整体分为几层：

- 客户端运行时，负责选择启动策略并连接服务端；
- Privileged Server 进程，只暴露项目自己的底层特权能力；
- `:priv-core` 中的 Binder/UserService 共享原语，供应用定义自己的能力；
- `:priv-server` 中的服务端 UserService 生命周期实现；
- 可选 Compose UI 帮助层和示例代码，用来展示运行时状态和启动流程，不扩展核心范围。

完整设计见 [docs/architecture.md](docs/architecture.md)。

## 语言和构建约束

- 除 hidden-api stub、framework mirror class、AIDL 兼容桥接外，所有手写源码都必须使用 Kotlin。
- 所有 Gradle 构建脚本都必须使用 Kotlin DSL，也就是 `.gradle.kts`。
- 普通业务模块不得新增 Java 源码。

## 开发规则

新增任何源码实现之前，必须先对照 [docs/project-constitution.md](docs/project-constitution.md)。如果某个设计需要高级 Android API 封装，它属于接入应用，不属于本项目。
