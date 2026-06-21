# 模块说明

本文档定义 `Priv Kit`（仓库名 `priv-kit`）的计划模块边界。

模块图的存在是为了保护项目范围。所有模块都必须遵守 [project-constitution.md](project-constitution.md)。

## 计划模块列表

- `:priv-core`
- `:priv-runtime`
- `:priv-server`
- `:priv-binder`
- `:priv-user-service`
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

| Gradle 模块 | Maven artifactId | Kotlin package 根 |
| --- | --- | --- |
| `:priv-core` | `priv-core` | `priv.kit.core` |
| `:priv-runtime` | `priv-runtime` | `priv.kit.runtime` |
| `:priv-server` | `priv-server` | `priv.kit.server` |
| `:priv-binder` | `priv-binder` | `priv.kit.binder` |
| `:priv-user-service` | `priv-user-service` | `priv.kit.userservice` |
| `:priv-bc` | `priv-bc` | `priv.kit.bc` |
| `:priv-ssl` | `priv-ssl` | `priv.kit.ssl` |
| `:priv-adb` | `priv-adb` | `priv.kit.adb` |
| `:priv-root` | `priv-root` | `priv.kit.root` |
| `:priv-delegate` | `priv-delegate` | `priv.kit.delegate` |
| `:priv-ui` | `priv-ui` | `priv.kit.ui` |
| `:priv-sample` | 不作为发布 artifact | `priv.kit.sample` |
| `:hidden-api` | 不作为发布 artifact | `priv.kit.hidden.api` |

除 `:hidden-api` 中的 framework mirror/stub 外，所有源码 package 必须位于 `priv.kit.*`。禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*`。

所有公开 API 必须使用完整单词 `Privilege*` 命名，例如 `PrivilegeKit`、`PrivilegeLaunchMode`、`PrivilegeServer`、`PrivilegeBinder`、`PrivilegeUserService`、`PrivilegeRuntime` 和 `PrivilegeConnection`。

禁止公开 API 使用 `Priv*` 缩写，例如 `PrivKit`、`PrivSession`、`PrivMode`、`PrivServer`、`PrivBinder` 和 `PrivUserService`。

## 依赖方向

推荐依赖方向：

```text
:priv-runtime
    -> :priv-core
    -> :priv-binder
    -> :priv-user-service
    -> :priv-adb
    -> :priv-root
    -> :priv-delegate

:priv-server
    -> :priv-core
    -> :priv-binder
    -> :priv-user-service

:priv-binder
    -> :priv-core

:priv-user-service
    -> :priv-core
    -> :priv-binder

:priv-bc

:priv-ssl

:priv-adb
    -> :priv-core
    -> :priv-bc
    -> :priv-ssl

:priv-root
    -> :priv-core

:priv-delegate
    -> :priv-core

:priv-ui
    -> :priv-runtime

:priv-sample
    -> 公开运行时、启动、Binder、UserService 和可选 UI 模块
```

实际 Gradle 依赖图可以在实现阶段细化，但所有权方向必须稳定：

- 共享契约来自 `:priv-core`；
- 编排属于 `:priv-runtime`；
- 特权进程行为属于 `:priv-server`；
- 启动实现留在各自模块；
- UI 依赖运行时，运行时不反向依赖 UI；
- 示例依赖公开模块，不应变成内部测试工具。

## `:priv-core`

职责：

- 共享契约；
- 共享值类型；
- 运行时状态模型；
- 启动结果模型；
- 服务端启动命令模型；
- 连接结果模型；
- 项目自有错误分类；
- 协议身份和版本契约。

允许：

- 原语化运行时和连接模型；
- 用于解耦模块的接口；
- 错误和诊断值类型；
- 标识项目协议的常量。

禁止：

- Android 系统服务封装；
- 项目自有 Binder AIDL 协议；
- Binder endpoint registry API；
- 启动命令构造逻辑；
- Root、ADB 或 Delegate transport 实现；
- UI 依赖；
- 只服务于示例的 helper；
- 面向输入、包、设置、app-ops 或 activity 管理的领域 API。

## `:priv-runtime`

职责：

- 客户端侧编排；
- 运行时状态机；
- 启动策略选择；
- 服务端连接生命周期；
- 重连行为；
- 面向应用的公开入口。

允许：

- 运行时配置；
- start、stop、connect、reconnect 生命周期；
- 作为原语暴露 Binder 和 UserService 入口；
- 状态观察；
- 启动策略组合；
- 构造项目自有 Privileged Server 的 `app_process` 启动命令；
- 以 runtime-only 依赖携带 `:priv-server`，让接入应用只需声明运行时模块；
- token、pending handshake、当前全局 server-binder 安装和 Binder death handling；
- 参考 shizuku-api 维护进程内单个当前 server-binder，重复握手保持同一 Binder，替换 Binder 时安装新的全局状态。

禁止：

- 直接实现 Root、ADB 或 Delegate 机制；
- 高级 Android 操作 API；
- UI toolkit 依赖；
- 只属于服务端的行为。

## `:priv-server`

职责：

- Privileged Server 进程入口；
- 特权侧 Binder 端点；
- 服务端生命周期；
- UserService 托管或协调；
- 项目协议执行。

允许：

- 服务端 bootstrap；
- 发布项目 Binder 端点；
- 在可行时检查客户端身份；
- 服务端侧 UserService 生命周期管线；
- 特权运行时诊断。

禁止：

- 可复用的高级特权操作；
- framework service facade API；
- 应用定义的业务逻辑；
- UI 代码。

## `:priv-binder`

职责：

- 运行时和服务端共享的 Binder 通信原语。
- 项目自有 Privileged Server Binder 协议。

允许：

- `IPrivilegeServer` 等运行时所需的项目自有 AIDL 契约；
- 单个 app-owned Binder endpoint 的句柄和注册生命周期；
- 显式目标 Binder 的 remote transact wrapper；
- Binder 端点注册和查找；
- Binder 端点注销；
- Binder 连接状态；
- Binder death 处理；
- server death、endpoint dead、endpoint not found 和远程调用失败的类型化异常；
- Binder 协议检查；
- 运行时操作所需的项目自有 Binder 契约。

禁止：

- Android 系统服务的类型化封装；
- 与本运行时无关的通用 Binder 库；
- 系统服务发现、系统服务枚举或可复用系统 Binder facade；
- endpoint id、多 endpoint 注册、endpoint 枚举、全局服务发现或多租户服务注册中心；
- package、input、settings、app-ops 或 activity API。

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

## `:priv-user-service`

职责：

- 面向应用自定义特权服务的 UserService Binder 管线。

允许：

- UserService 身份模型；
- start、bind、unbind 和 stop 契约；
- service Binder handoff；
- service 生命周期状态；
- service 失败报告。
- 可选的 reserved destroy transaction；
- 多个 `serviceClassName + tag` UserService 实例；
- `version` 变化触发同一实例替换，而不是创建并行实例；
- 默认独立 `app_process` 子进程模式；
- 显式 opt-in 的 server 进程嵌入模式；
- 无参、`android.content.Context`，或两者同时声明的 UserService 构造器；
- 可能被反射调用的 UserService 构造器需要由接入方用 `androidx.annotation.Keep` 显式保留；
- 独立进程 `Context` 构造器的 `makeApplication` 优先和 package `Context` 兜底；
- 嵌入式 `Context` 构造器的 package `Context` 初始化，不调用 `makeApplication`，且可在 package `Context` 创建失败时回退无参构造器；
- owner app death 时的 UserService 销毁策略；
- 独立 UserService destroy 后等待进程自行退出的可配置超时，并允许用负数关闭超时强杀兜底；
- UserService 子进程 ready/claim handoff 协议。

禁止：

- 应用业务逻辑；
- 内置特权操作服务；
- 高级系统操作模板；
- 执行 package、input、settings、app-ops 或 activity 管理的可复用 service 实现。
- 解析、生成或封装应用自定义 AIDL 业务接口；
- 把 UserService 扩展成跨应用服务发现、全局服务枚举或多租户服务注册中心。

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

允许：

- ADB 客户端证书生成所需的 DER/ASN.1 编码；
- ADB 客户端证书生成所需的 X.509 builder；
- 与 Bouncy Castle 行为对齐的字节级兼容测试。

禁止：

- 通用证书管理；
- 通用 PKI API；
- Android API 依赖；
- 非 ADB 启动所需的加密能力。

## `:priv-ssl`

职责：

- 项目内部 ADB Wireless Debugging pairing 所需的最小 BoringSSL 兼容能力。

允许：

- SPAKE2 pairing 消息生成和处理；
- ADB pairing 所需的 HKDF-SHA256；
- ADB pairing 所需的 AES-128-GCM 加解密；
- 与 BoringSSL/AOSP pairing 行为对齐的字节级兼容测试。

禁止：

- 通用 SSL/TLS 协议栈；
- 通用密码学工具箱；
- 证书、ASN.1 或 PKI 能力；
- ADB socket、mDNS 或启动命令执行逻辑。

## `:priv-root`

职责：

- 基于 Root 的服务端启动。

允许：

- 启动所需的 root 可用性检查；
- 通过 `su` 执行共享服务端启动命令；
- root 启动诊断；
- root 特有启动失败建模；
- 转换为共享启动结果。

禁止：

- 公开 root 命令库；
- 特权操作 helper；
- package、input、settings、app-ops 或 activity API。

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

禁止：

- 全局 delegate 市场；
- 多应用特权代理能力；
- 第三方特权操作路由；
- 系统能力抽象。

## `:priv-ui`

职责：

- 可选的 Jetpack Compose UI 帮助能力，用于运行时生命周期。

允许：

- Compose 状态展示；
- Compose 运行时生命周期控件；
- 在有价值时，围绕 `:priv-runtime` 状态模型提供 Compose 包装。

禁止：

- 传统 Android View UI 逻辑；
- 手写 `android.view.*` / `android.widget.*` 视图树；
- 通过 `Activity#setContentView(...)` 装配界面；
- 新增用于界面结构的 XML layout 文件；
- 特权操作控制台；
- 高级系统操作 composable；
- 核心运行时模块反向依赖 Compose。

## `:priv-sample`

职责：

- 演示项目支持的范围。
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
- 手写 `android.view.*` / `android.widget.*` 视图树；
- 调用 `Activity#setContentView(...)` 装配示例界面；
- 新增用于示例界面结构的 XML layout 文件；
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

Java 例外仅限于：

- hidden-api stub；
- framework mirror class；
- AIDL 兼容桥接。

任何 Java 文件都必须明确对应上述三类例外之一。

## 公开 API 评审清单

向任何模块新增公开 API 前，必须确认：

- 该 API 属于运行时、Binder、UserService 或启动；
- 该 API 使用 `Privilege*` 完整单词命名，不使用 `Priv*` 缩写；
- 该 API 所在源码 package 位于 `priv.kit.*`；
- 该 API 没有命名某个高级 Android 系统服务领域；
- 该 API 没有把 Android framework manager 复制成项目 facade；
- 该 API 可被单应用用于管理自己的服务端；
- 该 API 没有暗示本项目拥有下游特权操作。

如果不确定，保持 API 更低层，让应用自己构建领域层。
