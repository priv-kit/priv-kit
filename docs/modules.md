# 模块说明

本文档定义 `Priv Kit`（仓库名 `priv-kit`）的当前模块边界。所有模块都必须遵守 [project-constitution.md](project-constitution.md)。

当前源码按三类职责组织：

- 共享端：`:priv-core`
- 客户端：`:priv-runtime`
- 服务端：`:priv-server`

Binder 和 UserService 仍保留 `priv.kit.binder.*` / `priv.kit.userservice.*` package 分区，但不再对应独立 Gradle 模块。

## 模块列表

- `:priv-core`
- `:priv-runtime`
- `:priv-server`
- `:priv-bc`
- `:priv-ssl`
- `:priv-adb`
- `:priv-root`
- `:priv-delegate`
- `:priv-ui`
- `:priv-sample`
- `:hidden-api`

## Maven、Package 和 API 命名

发布模块的 Maven `groupId` 固定为 `io.github.priv-kit`。

示例：

```kotlin
implementation("io.github.priv-kit:priv-runtime:1.0.0")
```

模块命名、Maven `artifactId` 和 Kotlin package 根必须按下表保持一致：

| Gradle 模块 | Maven artifactId | Kotlin package 分区 |
| --- | --- | --- |
| `:priv-core` | `priv-core` | `priv.kit.core`, `priv.kit.binder`, `priv.kit.userservice` |
| `:priv-runtime` | `priv-runtime` | `priv.kit.runtime` |
| `:priv-server` | `priv-server` | `priv.kit.server`, 服务端侧 `priv.kit.userservice` 实现 |
| `:priv-bc` | `priv-bc` | `priv.kit.bc` |
| `:priv-ssl` | `priv-ssl` | `priv.kit.ssl` |
| `:priv-adb` | `priv-adb` | `priv.kit.adb` |
| `:priv-root` | `priv-root` | `priv.kit.root` |
| `:priv-delegate` | `priv-delegate` | `priv.kit.delegate` |
| `:priv-ui` | `priv-ui` | `priv.kit.ui` |
| `:priv-sample` | 不作为发布 artifact | `priv.kit.sample` |
| `:hidden-api` | 不作为发布 artifact | framework mirror/stub package |

除 `:hidden-api` 中的 framework mirror/stub 外，所有源码 package 必须位于 `priv.kit.*`。禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*`。

所有公开 API 必须使用完整单词 `Privilege*` 命名，例如 `PrivilegeLaunchMode`、`PrivilegeBinderEndpoint`、`PrivilegeUserServiceSpec`、`PrivilegeRuntime` 和 `PrivilegeUserServiceConnection`。禁止公开 API 使用 `Priv*` 缩写。

## 依赖方向

推荐依赖方向：

```text
:priv-runtime
    -> :priv-core
    -> :priv-adb
    -> :priv-root
    -> :priv-delegate
    -> runtimeOnly(:priv-server)

:priv-server
    -> :priv-core
    -> compileOnly(:hidden-api)

:priv-adb
    -> :priv-core
    -> :priv-bc
    -> :priv-ssl
    -> compileOnly(:hidden-api)

:priv-root
    -> :priv-core

:priv-delegate
    -> :priv-core

:priv-ui
    -> :priv-runtime

:priv-sample
    -> :priv-runtime
    -> :priv-ui
    -> 启动或演示所需模块
```

所有权方向必须稳定：

- 共享协议、AIDL、模型和原语来自 `:priv-core`；
- 客户端生命周期编排属于 `:priv-runtime`；
- 特权进程行为和服务端侧 UserService 实现属于 `:priv-server`；
- 启动实现留在各自模块；
- UI 依赖运行时，运行时不反向依赖 UI；
- 示例依赖公开模块，不应变成内部测试工具。

## `:priv-core`

职责：

- 共享协议和值类型；
- `priv.kit.core.*` 运行时模型、启动模型、协议版本和 handshake registry；
- `priv.kit.binder.*` AIDL、Binder endpoint 原语、共享异常、runtime/server 共享 registry、raw Binder wrapper；
- `priv.kit.userservice.*` AIDL、UserService spec/status/state/id、共享异常、wire contract、handshake registry；
- 运行时、服务端、示例和启动模块都需要理解的底层契约。

允许：

- 原语化运行时、Binder 和 UserService 模型；
- 项目自有 AIDL 协议；
- 显式目标 Binder 或显式系统服务名的 raw Binder transaction 桥；
- 错误和诊断值类型；
- 用于解耦模块的共享接口或常量。

禁止：

- Android 系统服务类型化封装；
- 启动命令构造逻辑；
- Root、ADB 或 Delegate transport 实现；
- UserService registry、manager、loader、process 或 destroy 实现；
- UI 依赖；
- 只服务于示例的 helper；
- 面向输入、包、设置、app-ops 或 activity 管理的领域 API。

## `:priv-runtime`

职责：

- 客户端 app 进程侧编排；
- `PrivilegeRuntime` 公开入口；
- 运行时状态、启动策略选择、服务端连接和重连；
- `PrivilegeRuntimeUserServiceClient`、`PrivilegeUserServiceConnection`；
- Manual Shell、owner token/config store、handshake provider；
- 通过 `runtimeOnly(:priv-server)` 携带服务端入口，让接入应用优先只依赖 `:priv-runtime`。

允许：

- start、stop、connect、reconnect 生命周期；
- 作为原语暴露 Binder 和 UserService 入口；
- 创建显式系统服务名的 raw Binder transaction 桥；
- 构造项目自有 Privileged Server 的 `app_process` 启动命令；
- token、pending handshake、当前全局 server-binder 安装和 Binder death handling。

禁止：

- 直接实现 Root、ADB 或 Delegate 机制；
- 服务端侧 UserService registry/loader/manager 实现；
- 高级 Android 操作 API；
- 类型化 Android 系统服务 API；
- UI toolkit 依赖。

## `:priv-server`

职责：

- Privileged Server 进程入口；
- 特权侧 Binder 端点；
- 服务端生命周期；
- raw Binder transaction 的服务端转发；
- 服务端侧 UserService 生命周期管线。

`priv.kit.userservice` 中以下服务端实现归属于本模块：

- `PrivilegeUserServiceRegistry`
- `PrivilegeUserServiceManagerBinder`
- `PrivilegeUserServiceHost`
- `PrivilegeUserServiceProcessHandle`
- `PrivilegeUserServiceLoader`
- `PrivilegeUserServiceDestroyer`
- `PrivilegeUserServiceGateBinder`
- `PrivilegeUserServiceProcessBinder`
- `PrivilegeUserServiceMain`
- `PrivilegeUserServiceProviderCall`

允许：

- 服务端 bootstrap；
- 发布项目 Binder 端点；
- 在可行时检查客户端身份；
- 管理 Binder endpoint slot；
- 启动、claim、绑定、销毁 UserService 子进程；
- 嵌入式 UserService 实例生命周期；
- 为 raw 系统服务 Binder 桥执行显式服务名解析和 transaction 转发。

禁止：

- 可复用的高级特权操作；
- framework service facade API；
- 应用定义的业务逻辑；
- UI 代码。

## `:hidden-api`

职责：

- 编译期 hidden framework API mirror/stub。

允许：

- Java stub；
- framework mirror class；
- 示例或测试所需的 hidden API 类型声明。

禁止：

- 运行时代码；
- 项目公开 API；
- 发布 artifact；
- 系统服务能力封装。

## `:priv-adb`

职责：

- 基于 ADB 的服务端启动。

允许：

- ADB 启动配置；
- Wireless Debugging pairing/connect；
- 通过 ADB shell 执行共享服务端启动命令；
- ADB 启动诊断；
- ADB 特有启动失败建模；
- 使用 hidden-api 编译期 stub 调用 ADB pairing 所需的 framework hidden API；
- 转换为共享启动结果。

调用方要求：

- Android P+ 上接入应用必须在使用 `:priv-adb` 前配置 hidden API exemption，例如在 `Application.attachBaseContext` 中调用 `HiddenApiBypass.addHiddenApiExemptions("L")`。

禁止：

- 公开 ADB 命令库；
- shell 便利 API；
- 面向无关系统操作的 ADB helper。

## `:priv-bc`

职责：

- 项目内部 ADB 启动客户端证书所需的最小 BC 兼容 ASN.1 / X.509 证书生成能力。

禁止通用证书管理、通用 PKI API、Android API 依赖，以及非 ADB 启动所需的加密能力。

## `:priv-ssl`

职责：

- 项目内部 ADB Wireless Debugging pairing 所需的最小 BoringSSL 兼容能力。

禁止通用 SSL/TLS 协议栈、通用密码学工具箱、证书/ASN.1/PKI 能力，以及 ADB socket、mDNS 或启动命令执行逻辑。

## `:priv-root`

职责：

- 基于 Root 的服务端启动。

允许：

- 启动所需的 root 可用性检查；
- 通过 `su` 执行共享服务端启动命令；
- root 启动诊断；
- root 特有启动失败建模；
- 转换为共享启动结果。

禁止公开 root 命令库、特权操作 helper、package/input/settings/app-ops/activity API。

## `:priv-delegate`

职责：

- 基于 Delegate 的服务端启动。

允许：

- 启动所需的 delegate 发现；
- 服务端启动所需的 delegate 连接；
- 通过 app-provided delegate executor 执行共享服务端启动命令；
- delegate 启动诊断；
- delegate 特有失败建模；
- 转换为共享启动结果。

禁止全局 delegate 市场、多应用特权代理能力、第三方特权操作路由和系统能力抽象。

## `:priv-ui`

职责：

- 可选 Jetpack Compose UI 帮助能力，用于运行时生命周期。

允许 Compose 状态展示、运行时生命周期控件，以及围绕 `:priv-runtime` 状态模型的 UI 包装。

禁止传统 Android View UI 逻辑、特权操作控制台、高级系统操作 composable，以及核心运行时模块反向依赖 Compose。

## `:priv-sample`

职责：

- 演示项目支持的范围；
- 使用 Jetpack Compose 实现示例界面。

允许演示：

- 选择启动策略；
- 启动服务端；
- 连接服务端；
- 观察运行时状态；
- 交换 Binder 端点；
- 启动或绑定应用自定义 UserService；
- 展示错误和恢复路径。

禁止演示：

- 传统 Android View UI 逻辑；
- 包安装；
- 输入注入；
- 设置修改；
- app-ops 修改；
- activity manager 操作；
- 任何让禁止的高级能力看起来像项目提供能力的示例。

## 各模块源码语言策略

所有模块默认规则：

- 手写源码使用 Kotlin；
- Gradle 构建脚本使用 Kotlin DSL；
- 普通业务模块不得包含 Java 源码。

Java 例外仅限于 hidden-api stub、framework mirror class、AIDL 兼容桥接。

## 公开 API 评审清单

向任何模块新增公开 API 前，必须确认：

- 该 API 属于运行时、Binder、UserService 或启动；
- 该 API 使用 `Privilege*` 完整单词命名，不使用 `Priv*` 缩写；
- 该 API 所在源码 package 位于 `priv.kit.*`；
- 该 API 没有命名某个高级 Android 系统服务领域，除非它只接受显式服务名并返回 raw Binder transaction 桥；
- 该 API 没有把 Android framework manager 复制成项目 facade；
- 该 API 可被单应用用于管理自己的服务端；
- 该 API 没有暗示本项目拥有下游特权操作。

如果不确定，保持 API 更低层，让应用自己构建领域层。
