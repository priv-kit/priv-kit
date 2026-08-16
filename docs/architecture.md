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
| `:priv-shared` | `priv-shared` | 窄 Android/JDK 底层机制、不变量和内部 hidden API 兼容扩展 |
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

:priv-shared
    -> compileOnly(:hidden-api)

:priv-sample
    -> implementation(:priv-core)
    -> implementation(:priv-ui)
```

所有权规则：

- `:priv-shared` 只能保存产品模块使用的无领域状态机制、窄 Android/JDK 原语和 hidden API 兼容逻辑。跨 Android 小版本的 hidden API 分派必须位于该模块，并且只能通过 `compileOnly(:hidden-api)` 获取声明。隐藏 interface 在部分受支持 API 上不存在时，使用接收 `IBinder` 的窄 compat wrapper 隔离类型；其他情况将兼容逻辑保留在对应隐藏类型上。该模块不得依赖 AndroidX、Compose、协程、Core 或 UI，不得包含资源、manifest、长期可变状态、启动策略、权限流程或业务编排。
- `:priv-core` 拥有运行时生命周期、Root、ADB、手动命令、外部启动、native starter、server entry、Binder、UserService、内部 AIDL、wire contract 和 handshake。
- `:priv-adb-crypto` 只包含 ADB 所需的证书与 pairing 加密，不依赖 Android API，不扩展为通用加密、证书、PKI、SSL 或 TLS 库。
- `:priv-ui` 只编排 Core 已有原语，不拥有 transport 和底层权限请求能力，Core 不得反向依赖 UI。
- `:priv-sample` 只演示项目已承诺的能力，不得承载发布模块的实现。它从同一源码树构建 `legacy` 和 `api29` 两个 product flavor：前者以 API 26 为最低版本并启用旧式 JNI 库打包，后者以 API 29 为最低版本并使用 AGP 现代打包。
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
- 用户手动执行 native starter 命令
- 能在 shell、root 或兼容身份中执行应用代码或启动命令的外部入口

Root 和 ADB 的 transport 与诊断留在 `:priv-core` 内部。Core 公开 native starter 命令，并在内部为受协调的启动组装同一命令。`minSdk < 29` 的应用只允许执行安装时解压到 `nativeLibraryDir` 的 starter；Android 10 及以上版本在安装元数据声明不解压 native 库时，通过系统 linker 执行 APK 或 ABI split 中未压缩的 starter，否则使用解压文件。手动命令和外部入口复用同一服务端入口与 Binder handoff，不得扩展为公开 shell helper、ADB helper 或特权操作库。

Native starter 必须在解析 APK、终止旧服务端或创建新进程前校验实际 UID，只允许 root（0）、system（1000）和 shell（2000）。Provider 侧保留独立的可信调用方校验作为纵深防御。

Owner Android userId 为 0 时，Core 生成的 starter 命令省略 userId 环境变量，由
starter 和服务端使用默认值 0；只有非 0 userId 才必须显式携带该参数。服务端进程名
使用内部 package/user 作用域 token 前缀。可读后缀在 user 0 下为
`<package>:priv-server`，仅非 0 user 使用
`<package>:priv-server-u<userId>`。旧进程发现优先精确匹配完整 `cmdline`；无法读取
时只允许匹配该作用域 token 对应的 `comm`，并验证进程实际 UID 属于受支持特权
身份；两者都无法检查则启动失败。
不得因另一 Android user 下存在同包名而终止其服务。
公开手动启动命令不得携带 launch correlation ID；该 ID 只用于进程内受协调的
Root、ADB 和外部启动流程，并且仅在非空时写入命令。

重复执行 starter 采用 kill-first 语义：先无副作用地完成 `/proc` 快照、进程身份和
signal 权限预检，再向所有已验证的旧服务端发送 `SIGKILL`，并在创建新进程前确认其
进程名已从 `/proc` 消失。同 UID 或 root 可正常完成；如果调用
身份无权终止旧进程、无法扫描或无法确认退出，本次 starter 必须以稳定错误标识和
非零退出码终止，不得创建候选服务端、不得要求旧服务端处理自销毁消息。主进程存在
时通过 Binder death 和后续初始握手观察断开、再连接；主进程不存在时，同一命令仍可
独立完成 owner userId 作用域内的替换并让新服务端等待后续 owner 重连。

外部启动集成的通用 runner、特权端 host、进程执行、日志管道、完成、超时和并发处理可以属于 Core。第三方绑定代码和应用自有 AIDL 必须留在应用侧、可选集成或 sample。

入口进程的身份不等于最终服务端身份。服务端必须基于实际进程身份报告和校验 uid、pid、package、协议版本及启动关联。运行时只可为自身启动闭环授予必要且有限的能力，不得成为通用授权代理。

## Binder 原语

Binder 支持只负责底层连接和 transaction：

- Binder 连接生命周期与 death 观察
- 与当前 Privileged Server 进程同生命周期、无业务 transaction 的 Binder token
- 显式目标 Binder 的 raw transaction
- 显式系统服务名的 raw transaction
- transaction 错误和服务端不可用状态
- 运行时内部所需的项目自有类型化契约

项目不得为 package、input、settings、app-ops、activity 等系统服务提供类型化 facade，也不得提供系统服务领域枚举或策略 API。

公开的 server lifecycle Binder 只作为跨进程 death token。它必须与内部
`IPrivilegeServer` 控制 Binder 分离，不得附加业务 interface 或自定义 transaction；
同一 server 进程内保持 Binder identity 稳定，server 替换后必须返回新 token。
该 token 必须与控制 Binder 在同一次 handshake 中传递，并作为
`PrivilegeServerInfo` 连接快照的一部分发布，不能通过后续 IPC 获取另一个时刻的 token。
`PrivilegeServerInfo` 只能由 Core 根据已验证的 handshake 构造，并按对象 identity
表示一次已接受的连接；它不是允许调用方复制或重组字段的 value/data class。

唯一保留的 package permission 例外是 `Privilege.checkPermission(...)`、`Privilege.grantRuntimePermission(...)` 和 `Privilege.revokeRuntimePermission(...)` 三个无策略的 framework pass-through。它们不得扩展为权限组、批处理、app-ops、安装流程、设备选择、撤销原因或授权策略抽象。

如果 transaction 需要 fallback，必须保留原始结果的不确定性。连接中断后不能在无法判断服务端是否已经执行时自动重试具有副作用的调用。

## UserService 管线

UserService 由接入应用定义业务接口和实现，项目只管理：

- service identity
- start、bind、unbind 和 stop
- 进程与连接状态
- 客户端、runtime、server 和 UserService 之间的 Binder handoff

实例身份由 `serviceClassName` 和 `tag` 共同确定。版本兼容时可复用实例，版本变化时必须有明确替换语义。

公开的 start、bind、unbind 和 stop 是挂起函数。start、bind 和 stop 可取消；unbind 一旦进入就必须在不可取消上下文中完成。它们的内部协议必须使用 operation id、异步回调和接收确认，不能让 Binder 调用线程等待服务锁或独立进程就绪。异步操作与清理执行器都必须限制并发线程和等待队列；待处理操作必须持有有界配额，直到接收确认或取消清理完成，取消排队任务时必须及时释放队列容量。按服务串行化使用固定条带锁，不能按外部可变 service id 永久累积锁对象。取消操作必须移除待处理状态，并回收仅为该操作创建、但尚未被客户端确认接收的进程或连接。连接 unbind 必须保持幂等；独立 UserService 进程不接收逐连接 unbind。等待独立进程就绪时不得持有全局 registry 锁，以免阻塞其他 UserService 实例。

嵌入 server 进程的实例不得终止 server 进程。独立进程实例在完成销毁和连接清理后可以退出。需要被反射构造的入口和构造函数必须保持可见性及混淆规则，应用业务 AIDL 不得进入发布模块。

## UI 和恢复

项目自带页面 UI 使用 Jetpack Compose。`:priv-ui` 与 `:priv-sample` 不得新增传统 View 页面、`setContentView(...)` 或页面 XML layout。

唯一例外是 Android Notification 的 `RemoteViews` XML。该布局只能服务通知，不得被 Activity、Fragment、Dialog、页面 composable 或示例界面复用。

UI 层只呈现和控制 Core 生命周期原语。静默恢复必须满足：

- 只重放 UI 最近一次成功确认的精确启动方式
- 不跨启动方式 fallback
- 不主动请求权限、外部授权或用户交互
- 只有匹配当前 UI operation 和 `launchCorrelationId` 的初始连接才能同时更新 methodId 并记录用户期望开启
- 非 UI 发起的初始连接和 owner 重连只能更新 Core 连接状态，不得推断用户的自动恢复意图
- 断连、server 死亡和恢复失败不得隐式关闭该期望
- 只有用户确认停止或关闭自动恢复时才能记录关闭
- UI 可以通过只读 `StateFlow` 公开该期望，但不得把它并入 Core 的实际连接状态或向观察者开放写权限

内置启动方式按钮在服务端已连接时必须先询问是否重新启动。取消不产生启动副作用；
继续才提交一次 replacement start。若 starter 无权终止旧进程，UI 通过 Snackbar
报告旧服务结束失败并结束本次启动。

需要用户决定后才能继续的前台流程必须让原 ViewModel 协程挂起等待结果。Compose
只呈现待决请求并返回确认、取消或其它明确结果；结果处理函数不得脱离原调用上下文
重新构造后续启动或配对流程。ViewModel 关闭时必须取消其待决请求；依赖 Activity
Result 或通知交互的请求在最后一个实际 host 离开时也必须取消并释放其启动门。

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
