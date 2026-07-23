# 模块说明

本文档定义 `Priv Kit`（仓库名 `priv-kit`）的当前模块边界。所有模块都必须遵守 [project-constitution.md](project-constitution.md)。

## 模块列表

- `:priv-shared`
- `:priv-core`
- `:priv-adb-crypto`
- `:priv-ui`
- `:priv-sample`
- `:hidden-api`

## Maven、Package 和 API 命名

发布模块的 Maven `groupId` 固定为 `io.github.priv-kit`。

示例：

```kotlin
implementation("io.github.priv-kit:priv-core:1.0.0")
```

模块命名、Maven `artifactId` 和 Kotlin package 根必须按下表保持一致：

| Gradle 模块 | Maven artifactId | Kotlin package 分区 |
| --- | --- | --- |
| `:priv-shared` | `priv-shared` | `priv.kit.shared` |
| `:priv-core` | `priv-core` | `priv.kit.core`, `priv.kit.core.binder`, `priv.kit.core.userservice`, `priv.kit.core.adb`, `priv.kit.core.internal.*` |
| `:priv-adb-crypto` | `priv-adb-crypto` | `priv.kit.adb.crypto.certificate`, `priv.kit.adb.crypto.pairing` |
| `:priv-ui` | `priv-ui` | `priv.kit.ui` |
| `:priv-sample` | 不作为发布 artifact | `priv.kit.sample`, `priv.kit.sample.common`, `priv.kit.sample.home`, `priv.kit.sample.debug`, `priv.kit.sample.userservice`, `priv.kit.sample.startup` |
| `:hidden-api` | 不作为发布 artifact | framework mirror/stub package |

除 `:hidden-api` 中的 framework mirror/stub 外，所有源码 package 必须位于 `priv.kit.*`。公开 API 必须使用完整单词 `Privilege` 或 `Privilege*` 命名，禁止公开 API 使用 `Priv*` 缩写。

## API 承诺边界

当前只承诺接入应用引用 `priv-core` 或 `priv-ui` 后可见的编译期 API：

- `:priv-core` 自身 public API；
- `:priv-ui` 自身 public API；
- `:priv-ui` 通过 `api(project(":priv-core"))` 传递暴露的 runtime API。

`priv-shared` 和 `priv-adb-crypto` 即使发布，也默认属于 runtime/UI 的实现依赖。它们不属于 Android runtime/UI 接入面；普通消费者不应直接依赖它们，且 `priv-core` / `priv-ui` 的公开签名不得暴露其中的类型。

`priv-shared` 的符号只因需要跨 artifact 编译而在字节码层公开。所有符号必须位于 `priv.kit.shared`，并仅由文档定义为实现细节；显式依赖该 artifact 不产生兼容性承诺。

`priv.kit.core.internal.*` 下的类型属于实现细节。少数 `app_process` main class、ContentProvider 或 AIDL 生成类型可能因 Android/JVM 反射要求在字节码层可见，但它们不构成公开 API。

## 依赖方向

推荐依赖方向：

```text
:priv-core
    -> implementation(:priv-shared)
    -> implementation(:priv-adb-crypto)
    -> compileOnly(:hidden-api)

:priv-ui
    -> api(:priv-core)
    -> implementation(:priv-shared)

:priv-sample
    -> implementation(:priv-core)
    -> implementation(:priv-ui)
    -> 示例所需第三方入口
```

所有权方向必须稳定：

- 只有 runtime 与 UI 都已在生产代码中实际使用的无领域状态 Android/JDK 底层机制和不变量归属 `:priv-shared`；
- 运行时生命周期、Root、ADB、手动 shell、外部启动、server entry、Binder/UserService 原语和内部协议都归属 `:priv-core`；
- ADB pairing/certificate crypto 的非 Android 实现归属 `:priv-adb-crypto`；
- UI 依赖运行时，运行时不反向依赖 UI；
- 示例依赖公开模块，不应变成内部测试工具。

## `:priv-shared`

职责：

- 为 `:priv-core` 与 `:priv-ui` 保存一致的 `.priv-kit` 私有目录路径规则；
- 提供宿主 merged manifest 权限声明查询和 `Context` 到 app-private storage 的适配；
- 提供原子二进制文件读写、受限深度的异常诊断文本、ADB 设备名清理和精确的 ADB 授权失败消息匹配；
- 保存两侧有意共享的 ADB loopback、默认 TCP 端口、端口范围、配对码规则、启动超时、ADB 授权超时和设备名长度默认值。

约束：

- 必须是窄 Android Library，生产代码只能依赖 Android SDK/JDK，不得依赖 AndroidX、Compose、协程、`:priv-core` 或 `:priv-ui`；
- 不得包含资源、manifest 权限/组件/元数据，也不得拥有 Android 长期可变状态；
- 只能通过 `implementation` 被 runtime/UI 引用，不得出现在其公开签名或普通消费者编译类路径中；
- 不得拥有 `PrivilegeUiConfig`、`PrivilegeConfig`、methodId 模型、silent runner、start gate、外部 Provider、权限流程、UI 状态或 transport 策略；
- 不得扩张为通用工具箱；新增代码必须证明 runtime 与 UI 均已存在实际调用点。

## `:priv-core`

职责：

- 客户端 app 进程侧编排；
- `Privilege` 公开入口；
- 运行时状态、启动策略选择、服务端连接和重连；
- owner 进程启动的 `ContentObserver` 通知、注册失败时的 `/proc` 轮询 fallback，以及不变的主动重连策略；
- 前台启动、静默启动与 owner 自动重连共享的进程内启动提交仲裁；
- Root 启动的 `su` 可用性检查、命令执行和启动诊断；
- ADB pairing/connect、ADB 启动配置、ADB 启动诊断和 TCP 复用；
- ADB 启动内部可选的 Wireless Debugging 临时开关管理；
- Privileged Server 连接后为 owner package 补授启动自举所需的有限权限；
- Shell Start Command、启动关联、运行时内存配置和 handshake provider；
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
- 为用户手动执行或具备代码执行能力的外部启动入口提供启动命令、主进程桥接 runner、特权端 host，以及底层外部进程执行与日志接收 helper；第三方绑定和应用自有 AIDL 不进入 runtime。

禁止：

- 公开 root 命令库、ADB 命令库、shell helper 库或特权操作 helper；
- 公开 package/input/settings/app-ops/activity 领域 facade；
- 高级 Android 操作 API；
- 类型化 Android 系统服务 API；
- UI toolkit 依赖；
- 把 `priv.kit.core.internal.*` 或 `priv.kit.shared` 类型作为公开签名暴露。

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

- 可选 Jetpack Compose UI 帮助能力，用于运行时生命周期；
- 记录 UI 管理的最近一次成功启动方式，并在无 `Activity` 场景精确静默重放该 runtime 启动原语；
- 保存用户是否期望启用特权功能，并据此约束自动恢复入口。

允许 Compose 状态展示、运行时生命周期控件、围绕 `:priv-core` 状态模型的 UI 包装，以及在应用私有 `.priv-kit` 目录保存一个原始启动 methodId 和一个严格单字节期望状态。只有前台已提交的精确方法收到同时匹配当前 operation 与当前 `launchCorrelationId` 的 `INITIAL_LAUNCH` 连接且未先取消时才写入 methodId；静默启动、`OWNER_RECONNECT`、已有连接和其他被动连接不得改写 methodId。非导出初始化 Provider 在 core runtime 初始化之后、应用 Provider 对外发布之前安装监听；每个被 runtime 接受的 `INITIAL_LAUNCH` 都将期望状态写为 `1`，包括复制外置 shell 命令启动的 server；`OWNER_RECONNECT`、断连、server 死亡和恢复失败保持原值。只有内置 UI 中已确认的停止动作或断连提示卡片的“关闭自动恢复”动作可以写入 `0`。期望状态为 `1` 且 runtime 断开或失败时显示提示卡片，不提供常驻开关。受期望状态约束的静默重放必须由调用方显式传入 `PrivilegeUiConfig`，不得执行跨方式 fallback、权限请求、外部 Provider 授权请求或用户提示。前台与静默启动共用同一个互斥启动门并采用先获得者执行、无排队和无抢占；已受理的前台启动副作用必须持有绑定同一 ViewModel 所有者的可嵌套租约直至其工作完成，权限事务还必须绑定实际 Scaffold host，并在最后一个 host 离开时清理。静默启动持有期间内置 UI 必须拒绝新的副作用入口，并在释放后完成 runtime 状态对账才重新启用。owner 自动重连由 runtime arbiter 协调：启动提交前 reconnect 优先，提交后当前前台或静默启动优先。两层协调都只覆盖当前进程；多进程应用必须只在一个指定进程初始化并触发 Priv Kit 启动。

弹窗、权限和外部授权作为 ViewModel 所有协程中的可取消挂起点衔接，Compose/第三方回调只负责提交结果。外部 Provider 统一使用 suspend 契约，不保留前台状态对账；挂起的权限事务仍须绑定实际 Scaffold host，并在最后一个 host 真正离开时清理，配置变更由同一 ViewModel 的新 host 接管。

允许为 Android `Notification` 自定义内容新增仅供 `RemoteViews` 使用的 XML layout，例如通知配对码控制面板。

禁止传统 Android View 页面 UI 逻辑、特权操作控制台、高级系统操作 composable，以及核心运行时模块反向依赖 Compose。通知 `RemoteViews` XML 不得被 Activity、Fragment、Dialog、页面 composable 或示例界面 inflate。

## `:priv-sample`

职责：

- 演示项目支持的范围；
- 使用 Jetpack Compose 实现示例界面。

package 分区：

- `priv.kit.sample` 承载应用入口、根导航和应用级主题；
- `priv.kit.sample.common` 承载 Debug 与 Startup 集成共同复用的少量诊断格式化；
- `priv.kit.sample.home` 承载 Home 主页；
- `priv.kit.sample.debug` 承载 Connection、Binder、UserService 调试页面，以及对应的状态、ViewModel、回调、运行时操作、生命周期协调和 hidden API 探针；
- `priv.kit.sample.userservice` 承载应用自有的 UserService 实现、共享状态和 AIDL 契约；
- `priv.kit.sample.startup` 承载对 `:priv-ui` 的页面、配置、ViewModel、自动恢复和通知配对服务适配，以及应用自有的 Shizuku 外部启动桥及其 AIDL 契约；
- `:priv-sample` 中直接导入 `priv.kit.ui.*` 的源码必须位于 `priv.kit.sample.startup`。
- Debug 监听和后台探针仅在 Debug destination 仍位于根 back stack 时启用，Home 与直接进入 Privilege UI 不初始化 Debug 子系统。

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
