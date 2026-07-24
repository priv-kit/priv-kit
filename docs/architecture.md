# Priv Kit 维护架构

本文档记录仓库中需要长期稳定的设计边界和维护规则。具体 API 用法以公开文档和源码为准，模块实现细节以代码为准。

## 项目定位

`Priv Kit` 是面向单应用的自管理 Privileged Runtime。它帮助应用启动、连接并管理自己的 Privileged Server，提供运行时生命周期、启动入口、底层 Binder 原语和 UserService 管线。

项目提供特权执行基础设施，不提供特权 Android 操作库，也不提供 AndroidX 风格的系统 API 兼容层。

允许的能力范围：

- 启动、停止、连接、重连和观察 Privileged Server
- Root、ADB、手动命令和具备代码执行能力的外部启动入口
- 显式目标 Binder 或显式系统服务名的 raw Binder transaction
- 应用自定义 UserService 的启动、绑定、停止和状态观察
- 围绕同一组运行时原语的可选 Compose UI

禁止把包管理、输入、设置、app-ops、ActivityManager、设备自动化或高级 shell 操作包装成项目公开 API。这些领域能力应由接入应用或下游库基于 Binder、UserService 自行实现。

本项目只服务一个应用管理自己的服务端，不得发展为设备级共享守护进程、多租户注册中心、插件市场或通用权限代理。

## 命名和 API 承诺

固定项目标识：

- GitHub Organization：`priv-kit`
- GitHub Repository：`priv-kit`
- 对外名称：`Priv Kit`
- Maven `groupId`：`io.github.priv-kit`

除 `:hidden-api` 的 framework mirror 和 stub 外，源码 package 必须位于 `priv.kit.*`。公开 API 必须使用完整的 `Privilege*` 命名，不得使用 `Priv*` 缩写。

接入应用的兼容性承诺只覆盖：

- `io.github.priv-kit:priv-core`
- `io.github.priv-kit:priv-ui`
- 上述模块通过 Gradle `api(...)` 传递暴露的类型

`priv-shared` 和 `priv-adb-crypto` 即使发布到 Maven Central，也只是实现依赖。普通消费者不应直接依赖它们，`priv-core` 和 `priv-ui` 的公开签名不得暴露其中的类型。

`priv.kit.core.internal.*`、反射入口、ContentProvider 和内部 AIDL 生成类型即使在字节码层可见，也不构成公开 API。

## 模块和依赖方向

| Gradle 模块 | 发布名称 | 所有权 |
| --- | --- | --- |
| `:priv-shared` | `priv-shared` | Core 与 UI 已共同使用的窄 Android/JDK 底层机制和不变量 |
| `:priv-core` | `priv-core` | Runtime、启动入口、server、Binder、UserService 和内部协议 |
| `:priv-adb-crypto` | `priv-adb-crypto` | ADB 证书与 Wireless Debugging pairing 所需的最小 Kotlin/JVM 加密实现 |
| `:priv-ui` | `priv-ui` | 可选 Compose 生命周期 UI 和精确静默恢复 |
| `:priv-sample` | 不发布 | 公开能力的示例 |
| `:hidden-api` | 不发布 | 编译期 framework mirror 和 stub |

依赖方向固定为：

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
```

所有权规则：

- `:priv-shared` 只能保存 Core 与 UI 已有实际调用点的无领域状态机制，不得依赖 AndroidX、Compose、协程、Core 或 UI，不得包含资源、manifest、长期可变状态、启动策略、权限流程或业务编排。
- `:priv-core` 拥有运行时生命周期、Root、ADB、手动命令、外部启动、native starter、server entry、Binder、UserService、内部 AIDL、wire contract 和 handshake。
- `:priv-adb-crypto` 只包含 ADB 所需的证书与 pairing 加密，不依赖 Android API，不扩展为通用加密、证书、PKI、SSL 或 TLS 库。
- `:priv-ui` 只编排 Core 已有原语，不拥有 transport 和底层权限请求能力，Core 不得反向依赖 UI。
- `:priv-sample` 只演示项目已承诺的能力，不得承载发布模块的实现。
- `:hidden-api` 只提供编译期声明，不得包含运行时代码、公开 API 或发布产物。

## 运行时闭环

客户端运行时维护服务端启动、连接、死亡观察和重连。启动入口最终必须收敛到同一条 Binder handoff，服务端身份和本次启动关联必须在接受连接前完成校验。

必须保持以下不变量：

- 一个应用只在一个指定进程初始化和触发 Priv Kit 启动。
- 前台启动、静默启动和 owner 自动重连由同一进程内仲裁器协调。
- 启动提交前，已有 owner 重连优先；提交后，当前已受理的前台或静默启动优先。
- 初始连接必须同时匹配当前 operation 和当前 `launchCorrelationId`，旧启动结果不得覆盖新状态。
- owner 重连不得替换当前已提交启动的结果。
- 连接锁内不得执行可能回调应用或发起 IPC 的工作。
- Binder 死亡、连接失败和恢复失败必须以可观察状态报告，不得伪装为成功。

协调只覆盖当前进程，多进程应用不能依赖它完成跨进程互斥。

## 启动入口

支持的启动方式只有：

- Root
- ADB
- 用户手动执行基于 native starter SO 路径组装的命令
- 能在 shell、root 或兼容身份中执行应用代码或启动命令的外部入口

Root 和 ADB 的 transport 与诊断留在 `:priv-core` 内部。Core 公开 native starter SO 路径，并在内部为受协调的启动组装命令。手动命令和外部入口复用同一服务端入口与 Binder handoff，不得扩展为公开 shell helper、ADB helper 或特权操作库。

外部启动集成的通用 runner、特权端 host、进程执行、日志管道、完成、超时和并发处理可以属于 Core。第三方绑定代码和应用自有 AIDL 必须留在应用侧、可选集成或 sample。

入口进程的身份不等于最终服务端身份。服务端必须基于实际进程身份报告和校验 uid、pid、package、协议版本及启动关联。运行时只可为自身启动闭环授予必要且有限的能力，不得成为通用授权代理。

## Binder 原语

Binder 支持只负责底层连接和 transaction：

- Binder 连接生命周期与 death 观察
- 显式目标 Binder 的 raw transaction
- 显式系统服务名的 raw transaction
- transaction 错误和服务端不可用状态
- 运行时内部所需的项目自有类型化契约

项目不得为 package、input、settings、app-ops、activity 等系统服务提供类型化 facade，也不得提供系统服务领域枚举或策略 API。

如果 transaction 需要 fallback，必须保留原始结果的不确定性。连接中断后不能在无法判断服务端是否已经执行时自动重试具有副作用的调用。

## UserService 管线

UserService 由接入应用定义业务接口和实现，项目只管理：

- service identity
- start、bind、unbind 和 stop
- 进程与连接状态
- 客户端、runtime、server 和 UserService 之间的 Binder handoff

实例身份由 `serviceClassName` 和 `tag` 共同确定。版本兼容时可复用实例，版本变化时必须有明确替换语义。

嵌入 server 进程的实例不得终止 server 进程。独立进程实例在完成销毁和连接清理后可以退出。需要被反射构造的入口和构造函数必须保持可见性及混淆规则，应用业务 AIDL 不得进入发布模块。

## UI 和恢复

项目自带页面 UI 使用 Jetpack Compose。`:priv-ui` 与 `:priv-sample` 不得新增传统 View 页面、`setContentView(...)` 或页面 XML layout。

唯一例外是 Android Notification 的 `RemoteViews` XML。该布局只能服务通知，不得被 Activity、Fragment、Dialog、页面 composable 或示例界面复用。

UI 层只呈现和控制 Core 生命周期原语。静默恢复必须满足：

- 只重放 UI 最近一次成功确认的精确启动方式
- 不跨启动方式 fallback
- 不主动请求权限、外部授权或用户交互
- 只有匹配当前 operation 和 `launchCorrelationId` 的初始连接才能更新 methodId
- 被 runtime 接受的初始启动可记录用户期望开启
- 断连、server 死亡和恢复失败不得隐式关闭该期望
- 只有用户确认停止或关闭自动恢复时才能记录关闭

前台和静默启动共用互斥启动门，采用先获得者执行、无排队、无抢占。配置变更可以由同一 ViewModel 的新 host 接管，但最后一个实际 host 离开时必须清理挂起的权限事务。

## 源码和仓库工具

Gradle 产品模块的手写源码使用 Kotlin，以下兼容场景除外：

- hidden API stub
- framework mirror class
- AIDL 兼容桥接

Gradle 构建脚本使用 Kotlin DSL 和 `.gradle.kts`。

Node.js、TypeScript 和 SVG 只允许用于文档、仓库检查和 CI，不得进入 Gradle 产品模块、Android/JVM artifact、示例运行时或公开 Android API。可执行工具源码统一使用 `.ts`，禁止 `.js`、`.mjs` 和 `.cjs`。

VitePress 工程根目录和公开源目录均为 `website`。每个公开英文 Markdown 页面都必须有位于 `website/zh` 的路径等价简体中文页面。内部维护或 AI 可读的 Markdown 只允许放在 `docs`，不得放入 `website`。文档站使用 VitePress 默认主题，不添加自定义主题 CSS。

`.github/workflows/website.yml` 使用 Cloudflare Wrangler Action 将构建产物部署到 Cloudflare Pages 项目 `priv-kit`。公开地址为 `https://priv-kit.pages.dev`，部署需要仓库 Secrets `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID`。

## 变更门禁

新增公开 API、模块、示例或文档前，必须确认：

1. 变更是否只服务运行时、启动、Binder 或 UserService。
2. 应用是否本可基于 Binder 或 UserService 自行实现该领域能力。
3. 名称是否暗示高级 Android 系统服务封装。
4. 是否在 Android framework API 之上创建第二层领域抽象。
5. 是否会鼓励 `Privilege.input.tap(...)` 或 `Privilege.package.install(...)` 一类 API。
6. 公开 API 是否使用 `Privilege*` 完整命名。
7. 源码 package 是否位于 `priv.kit.*`。
8. 新依赖和代码是否归属于正确模块。

第 1 项为否时拒绝变更。第 2 至第 5 项任一为是时默认拒绝变更。第 6 至第 8 项任一不满足时拒绝变更。

如果确实需要改变这些边界，应先修改本文档，并在同一变更中更新实现、公开文档和验证。

## 验证

产品模块：

```shell
./gradlew publishToMavenLocal
./gradlew :priv-sample:assembleRelease
```

公开文档，在 `website` 目录执行：

```shell
pnpm check
pnpm build
```
