# 模块说明

本文档定义 `Priv Kit`（仓库名 `priv-kit`）的当前模块边界。所有模块都必须遵守 [project-constitution.md](project-constitution.md)。

## 模块列表

- `:priv-runtime`
- `:priv-adb-crypto`
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
| `:priv-runtime` | `priv-runtime` | `priv.kit`, `priv.kit.binder`, `priv.kit.userservice`, `priv.kit.adb`, `priv.kit.internal.*` |
| `:priv-adb-crypto` | `priv-adb-crypto` | `priv.kit.adb.crypto.certificate`, `priv.kit.adb.crypto.pairing` |
| `:priv-ui` | `priv-ui` | `priv.kit.ui` |
| `:priv-sample` | 不作为发布 artifact | `priv.kit.sample` |
| `:hidden-api` | 不作为发布 artifact | framework mirror/stub package |

除 `:hidden-api` 中的 framework mirror/stub 外，所有源码 package 必须位于 `priv.kit.*`。公开 API 必须使用完整单词 `Privilege` 或 `Privilege*` 命名，禁止公开 API 使用 `Priv*` 缩写。

## API 承诺边界

当前只承诺接入应用引用 `priv-runtime` 或 `priv-ui` 后可见的编译期 API：

- `:priv-runtime` 自身 public API；
- `:priv-ui` 自身 public API；
- `:priv-ui` 通过 `api(project(":priv-runtime"))` 传递暴露的 runtime API。

`priv-adb-crypto` 即使发布，也默认属于非 Android 的 ADB crypto 实现模块。它不属于 Android runtime/UI 接入面。

`priv.kit.internal.*` 下的类型属于实现细节。少数 `app_process` main class、ContentProvider 或 AIDL 生成类型可能因 Android/JVM 反射要求在字节码层可见，但它们不构成公开 API。

## 依赖方向

推荐依赖方向：

```text
:priv-runtime
    -> implementation(:priv-adb-crypto)
    -> compileOnly(:hidden-api)

:priv-ui
    -> api(:priv-runtime)

:priv-sample
    -> implementation(:priv-runtime)
    -> implementation(:priv-ui)
    -> 示例所需第三方入口
```

所有权方向必须稳定：

- 运行时生命周期、Root、ADB、手动 shell、外部启动、server entry、Binder/UserService 原语和内部协议都归属 `:priv-runtime`；
- ADB pairing/certificate crypto 的非 Android 实现归属 `:priv-adb-crypto`；
- UI 依赖运行时，运行时不反向依赖 UI；
- 示例依赖公开模块，不应变成内部测试工具。

## `:priv-runtime`

职责：

- 客户端 app 进程侧编排；
- `Privilege` 公开入口；
- 运行时状态、启动策略选择、服务端连接和重连；
- Root 启动的 `su` 可用性检查、命令执行和启动诊断；
- ADB pairing/connect、ADB 启动配置、ADB 启动诊断和 TCP 复用；
- ADB 启动内部可选的 Wireless Debugging 临时开关管理；
- Privileged Server 连接后为 owner package 补授启动自举所需的有限权限；
- Shell Start Command、owner token store、运行时内存配置和 handshake provider；
- 通用 native starter 可执行文件；
- Privileged Server `app_process` 入口；
- 服务端 Binder 端点和 raw Binder transaction 转发；
- UserService start/bind/stop 管线、服务端 registry/loader/host/destroyer 和独立 UserService 子进程入口；
- 内部 AIDL、wire contract、handshake registry、protocol 和 launch command。

允许：

- start、stop、connect、reconnect 生命周期；
- 作为原语暴露 Binder 和 UserService 入口；
- 创建显式目标 Binder 的 raw Binder transaction 桥；
- 创建显式系统服务名的 raw Binder transaction 桥；
- 提供少量高频 Android framework 方法的显式参数透传桥；
- 构造项目自有 Privileged Server 的 `app_process` 启动命令；
- 打包供 shell/manual/ADB 通道复用的 native starter 可执行文件；
- 通过 root 或 ADB 执行共享服务端启动命令；
- 为用户手动执行或具备代码执行能力的外部启动入口提供启动命令、外部特权进程内执行 helper 和主进程日志接收 helper。

禁止：

- 公开 root 命令库、ADB 命令库、shell helper 库或特权操作 helper；
- 公开 package/input/settings/app-ops/activity 领域 facade；
- 高级 Android 操作 API；
- 类型化 Android 系统服务 API；
- UI toolkit 依赖；
- 把 `priv.kit.internal.*` 类型作为公开签名暴露。

## `:priv-adb-crypto`

职责：

- 项目内部 ADB 启动客户端证书所需的最小 X.509 / DER 生成能力；
- 项目内部 ADB Wireless Debugging pairing 所需的最小 BoringSSL 兼容 SPAKE2 / HKDF / AES-GCM 能力。

package 分区：

- `priv.kit.adb.crypto.certificate` 只承载 ADB 客户端证书生成；
- `priv.kit.adb.crypto.pairing` 只承载 Wireless Debugging pairing 加密上下文。

禁止通用 SSL/TLS 协议栈、通用密码学工具箱、通用证书管理、通用 PKI API、Android API 依赖，以及 ADB socket、mDNS 或启动命令执行逻辑。

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

## `:priv-ui`

职责：

- 可选 Jetpack Compose UI 帮助能力，用于运行时生命周期。

允许 Compose 状态展示、运行时生命周期控件，以及围绕 `:priv-runtime` 状态模型的 UI 包装。

允许为 Android `Notification` 自定义内容新增仅供 `RemoteViews` 使用的 XML layout，例如通知配对码控制面板。

禁止传统 Android View 页面 UI 逻辑、特权操作控制台、高级系统操作 composable，以及核心运行时模块反向依赖 Compose。通知 `RemoteViews` XML 不得被 Activity、Fragment、Dialog、页面 composable 或示例界面 inflate。

## `:priv-sample`

职责：

- 演示项目支持的范围；
- 使用 Jetpack Compose 实现示例界面。

允许演示：

- 选择启动策略；
- 启动服务端；
- 连接服务端；
- 观察运行时状态；
- 使用 raw Binder transaction 桥；
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

## 公开 API 评审清单

向任何模块新增公开 API 前，必须确认：

- 该 API 属于运行时、Binder、UserService 或启动；
- 该 API 使用 `Privilege*` 完整单词命名，不使用 `Priv*` 缩写；
- 该 API 所在源码 package 位于 `priv.kit.*`；
- 该 API 没有命名某个高级 Android 系统服务领域，除非它只接受显式服务名并返回 raw Binder transaction 桥；
- 该 API 没有把 Android framework manager 复制成项目 facade；若是高频 framework 方法透传，必须保留显式参数和原始返回语义；
- 该 API 可被单应用用于管理自己的服务端；
- 该 API 没有暗示本项目拥有下游特权操作。

如果不确定，保持 API 更低层，让应用自己构建领域层。
