# 架构设计

`Priv Kit`（仓库名 `priv-kit`）围绕一个小型特权运行时闭环组织：

1. 应用请求客户端运行时启动或连接；
2. 运行时选择启动策略；
3. 启动策略启动或触达 Privileged Server；
4. 运行时建立 Binder 连接；
5. 应用使用 Binder 或 UserService 原语运行自己的特权逻辑。

架构有意停在这些原语上。包管理、输入注入、设置修改、activity 控制、app-ops 修改等领域操作属于应用职责。

## 设计原则

- 公开接口保持原语化，不做领域封装。
- 将 Privileged Server 视为应用自有基础设施。
- 启动策略可替换，并隔离各自细节。
- Binder 和 UserService 是下游能力的扩展点。
- UI 模块只观察和控制生命周期。
- 优先提供明确状态和错误报告，不把恢复行为藏起来。
- 避免发展成兼容层。

## 命名约束

架构命名必须与 [project-constitution.md](project-constitution.md) 保持一致：

- GitHub Organization 和 Repository 都固定为 `priv-kit`。
- 项目对外名称固定为 `Priv Kit`。
- Maven `groupId` 固定为 `io.github.priv-kit`。
- 发布模块的 Maven `artifactId` 使用 `priv-*`：`priv-shared`、`priv-core`、`priv-adb-crypto`、`priv-ui`。
- Gradle 模块使用 `:priv-shared`、`:priv-core`、`:priv-adb-crypto`、`:priv-ui`、`:priv-sample`，以及内部编译期 stub 模块 `:hidden-api`。
- 除 `:hidden-api` 中的 framework mirror/stub 外，Kotlin package 统一使用 `priv.kit.*`，例如 `priv.kit.core`、`priv.kit.core.binder`、`priv.kit.core.userservice`、`priv.kit.core.adb`、`priv.kit.shared`、`priv.kit.core.internal.*`。
- 禁止使用 `io.github.xxx.*`、`io.github.priv.*`、`io.github.priv.kit.*` 或 `privkit.*` 作为源码 package。
- 公开 API 使用完整单词 `Privilege` 或 `Privilege*`，例如 `Privilege`、`PrivilegeKit`、`PrivilegeConnection`。
- 公开 API 禁止使用 `Priv*` 缩写，例如 `PrivKit`、`PrivSession`、`PrivRuntime`、`PrivConnection`。

示例依赖坐标：

```kotlin
implementation("io.github.priv-kit:priv-core:1.0.0")
```

## 语言和构建约束

除以下情况外，所有手写源码统一使用 Kotlin：

- hidden-api stub；
- framework mirror class；
- AIDL 兼容桥接。

所有 Gradle 脚本统一使用 Kotlin DSL，即 `*.gradle.kts`。

禁止在普通模块新增 Java 源码。

## 高层组件

模块依赖保持单向；`:priv-shared` 是实现依赖，不是接入应用的编译 API：

```text
:priv-ui      -- api -------------> :priv-core
:priv-ui      -- implementation --> :priv-shared
:priv-core    -- implementation --> :priv-shared
:priv-core    -- implementation --> :priv-adb-crypto
```

```text
Application
    |
    v
:priv-core
    |
    +-------------------+--------------------+----------------------+
    |                   |                    |                      |
    v                   v                    v                      v
root executor       adb transport     external/manual command   binder/userservice
    |                   |                    |                      |
    +-------------------+--------------------+----------------------+
                        |
                        v
              internal privileged server
```

`:priv-core` 是 Android 接入和内部运行时闭环。公开原语位于 `priv.kit.core`、`priv.kit.core.binder`、`priv.kit.core.userservice` 和 `priv.kit.core.adb`；handshake、protocol、AIDL、server entry、ADB raw transport 和服务端 UserService 实现位于 `priv.kit.core.internal.*`。

`:priv-shared` 是只依赖 Android SDK/JDK 的窄 Android 内部实现层，保存 runtime 与 UI 已经共同依赖的宿主 manifest 查询、app-private storage 适配、文件存储约定、诊断格式、ADB 文本不变量和共享默认值。它不包含资源或 manifest 声明/组件，不依赖 AndroidX，也不拥有启动策略、UI 配置、methodId 语义、权限流程或运行时状态；其字节码 public 符号仍属于文档声明的实现细节，且不允许出现在 `priv-core` / `priv-ui` 的公开签名中。

## 运行时生命周期

运行时是客户端侧的协调者，负责以下状态机：

- idle；
- starting；
- cancelling；
- connecting；
- connected；
- reconnecting；
- stopping；
- stopped；
- failed。

运行时必须提供足够信息，让应用决定下一步动作，但不得把这些状态转成领域化 Android 操作。

运行时预期职责：

- 接收启动配置；
- 选择启动策略；
- 启动或触达 Privileged Server；
- 建立 Binder 连接；
- 监听服务端死亡；
- 在配置允许时重连；
- 向可选 UI 模块暴露连接状态；
- 提供 Binder 和 UserService 原语入口。

owner 进程死亡后的默认重连是被动事件驱动：Privileged Server 在初次 handshake 后注册指向宿主 handshake provider authority 的 `ContentObserver`；指定 app 进程创建 provider 时发送进程启动通知，server 收到通知后才尝试 owner Binder handoff。Android 11 及以上使用 no-delay notification flag，避免后台 observer 通知被系统延迟。正常路径不持续扫描 `/proc`；只有 observer 注册失败时，才退回原有的进程轮询兼容路径。`activeReconnectOnOwnerDeath = true` 仍表示 server 主动重试调用 provider，并可能拉起 app 进程，不由 `ContentObserver` 替代。

前台启动、静默启动和 owner 自动重连分两层协调。UI 侧带所有者的 gate 让前台与静默启动保持先获得者执行、无排队和无抢占；runtime 侧进程内 arbiter 再把 owner reconnect 纳入同一启动提交边界。优先级依次是当前已连接或 ready 的 server、客户端启动提交前到达的 `OWNER_RECONNECT`、已经原子提交 runtime lease 的前台或静默启动。提交后到达的旧 server reconnect 被拒绝，新的客户端启动继续；operation 内每次真正产生启动副作用前都会生成独立 `launchCorrelationId`，只有同时匹配当前 operation 与当前 `launchCorrelationId` 的 `INITIAL_LAUNCH` 才能归因到这次尝试，前一次 fallback 或超时后迟到的 server 不会冒充后一次启动。该协调只覆盖一个指定 app 进程，多进程应用不得在多个进程分别初始化或触发 Priv Kit 启动。

## 服务端角色

Privileged Server 是以特权运行的运行时端点。

服务端预期职责：

- 使用启动策略传入的参数完成 bootstrap；
- 发布项目自有 Binder 端点；
- 在平台允许时验证连接客户端是否为目标应用；
- 管理 Binder 端点交换；
- 托管或协调 UserService 生命周期；
- 报告服务端身份、协议版本和生命周期状态。

服务端不得暴露项目定义的高级系统 API。少量高频 framework 方法可以作为显式参数的透传桥存在，但不得加入策略、发现、批处理或领域 facade。服务端可以执行应用自定义的 UserService 逻辑，但这些逻辑不属于本项目的公开能力面。

## 启动策略

项目支持四类启动入口：

- `:priv-core` 内部的 Root 启动；
- `:priv-core` 内部的 ADB 启动；
- 用户手动执行的 shell 启动命令；
- Shizuku UserService 或其他能够在 shell/root 等兼容身份中托管应用代码并执行启动命令的外部启动入口。

Root 和 ADB 策略把应用配置转换成服务端启动或连接尝试。手动 shell 和外部启动入口执行的是同一个 Shell Start Command，只是命令执行者不同；两者都复用同一条 Binder handoff。

ADB 策略可以在内部托管 ADB 状态恢复：当最终 merged manifest 仍声明 `WRITE_SECURE_SETTINGS`、宿主应用已被授予该权限，且启动需要动态无线调试端口时，runtime 可以临时打开 `adb_wifi_enabled`、通过 mDNS 发现 `_adb-tls-connect._tcp` 端口、启动服务端，并在启动尝试结束后关闭 Wireless Debugging。显式静态 TCP 启动若仍有持久化端口配置但监听不可达，则只写入 `ADB_ENABLED=1` 唤醒 `adbd`，等待监听恢复后重新鉴权，不要求 Wi-Fi，也不打开 `adb_wifi_enabled`。被动状态轮询不得触发这项写入。Privileged Server 连接成功后，可以通过 server 侧 `IPackageManager` 透传调用为 owner package 补授这一个启动权限。如果宿主应用通过 manifest merge 移除该权限声明，该策略视为被宿主明确禁用。该能力仅服务于 ADB 启动策略，不构成 Settings 操作 API，也不得被扩展成通用系统设置封装。

共享的 `app_process` 服务端启动命令由运行时根据内部启动值模型构造。供 shell/ADB 通道复用的 native starter 可执行文件也由 `:priv-core` 打包。Root 执行器只负责 `su` 执行通道和失败诊断；ADB 实现只负责 pairing/connect、ADB 命令执行通道和失败诊断。运行时负责启动关联、Root/ADB pending handshake、ready-server handoff、全局 server-binder 安装、Shell Start Command 和 death handling。

`priv-ui` 手动面板始终向用户展示直接执行当前 native starter 的
`adb shell <native-starter-path>` 宿主命令，不得把启动命令或可由 shell UID 执行的引导脚本
写入外部存储。应用专属外部目录不能在所有 Android 版本上被视为可信的可执行内容边界。这个 UI
包装不得改变 Root、ADB、外部启动入口或公开 `Privilege.createShellStartCommand()` 使用的共享
Shell Start Command。

app 侧 handshake provider 必须保持 exported，以便 shell、root 或外部启动入口启动的 server 回传 Binder。provider 使用 `android.permission.INTERACT_ACROSS_USERS_FULL` 阻止普通应用直接调用，并仅接受 root、system、shell 或 owner UID；协议不宣称能够区分或认证共享同一特权 UID/执行域的不同进程。由客户端发起的 Root/ADB 启动继续使用一次性 `launchCorrelationId` 关联当前 pending handshake，但可随时执行的 Shell Start Command 不携带长期认证凭据。

启动入口、命令执行者和服务端实际运行身份是三个概念：Root、ADB、手动 shell 或外部启动入口描述命令从哪里触发；服务端最终运行身份以 `PrivilegeServerInfo.uid` 和 `pid` 为准。运行时不再提供额外的 root/shell 分类，避免把设备上的实际 UID 强行归类为权限等级。

外部启动入口必须具备执行启动命令或托管应用启动代码的能力；仅提供授权 Binder 或权限 API、但不能执行代码的工具不属于 Priv Kit 启动策略。`priv-kit` 提供可随时执行的启动命令、主进程侧 `PrivilegeExternalStartup.runThroughBridge(...)`、特权端 `PrivilegeExternalStartupHost`，由 runtime 统一管理 `ParcelFileDescriptor` stdout/stderr 管道、实时日志、超时、并发与 `ResultReceiver` 完成通知；`runInCurrentProcess(...)` 和 `createReceiver(...)` 保留为底层 helper。Shizuku UserService 等第三方能力只负责绑定并用应用自有 Binder/AIDL 启动方法桥接这两端，相关依赖停留在应用侧 provider、可选集成模块或示例代码中，不成为核心运行时策略模块。runtime 不替应用做 Binder 调用方鉴权，应用必须限制桥接入口只允许可信调用方访问。

启动策略不得变成操作库。`Privilege.startRoot()` 可以通过 root 启动服务端，但不得提供用于包安装、输入事件、设置写入、app-ops 修改或其他系统操作的公开 root helper。

## Binder 架构

Binder 是最低层的特权通信原语。

Binder 支持应覆盖：

- 连接 Privileged Server 的 Binder 端点；
- Binder death recipient 处理；
- transaction 失败传播；
- 显式目标 Binder 的 remote transact 转发；
- 显式系统服务名的 raw remote transact 转发；
- 项目自有契约的协议和版本检查。

当前 Binder 原语由 `:priv-core` 的 `priv.kit.core.binder` package 分区承载，内部 AIDL 和服务端侧 transaction 执行归属于 `priv.kit.core.internal.*`：

- 内部 `IPrivilegeServer` 定义项目自有 Privileged Server Binder 协议；
- `PrivilegeBinderWrapper.fromBinder(...)` 将调用方已持有的显式目标 `IBinder` 的 `transact` 通过当前 Privileged Server 执行，并通过 `Privilege` 的全局 server-binder getter 在每次 transaction 前统一拦截 server 断连；
- `PrivilegeBinderWrapper.fromSystemService(...)` 默认在当前进程通过 hidden `ServiceManager.getService(name)` 获取目标 Binder，再复用 `fromBinder(...)` 的 raw transaction 桥；
- `PrivilegeBinderWrapper.fromSystemService(..., source = PrivilegeSystemServiceSource.SERVER_PROCESS)` 先确认当前 Privileged Server 进程能按显式系统服务名解析目标，再返回按服务名延迟解析和转发 transaction 的 raw Binder 桥，不向 app 暴露 server 进程内的真实 Binder；
- 项目自有 server control 调用在 server Binder 缺失、抛出 `DeadObjectException`，或其他 `RemoteException` 且对应 Binder 已确认死亡时，对外统一暴露 `PrivilegeServerUnavailableException`；UserService 与目标 Binder endpoint 的调用失败保留 raw Binder 语义，不推测死亡来源。
- `PrivilegeBinderCall.orElse(...)` 只处理 `PrivilegeServerUnavailableException` 与直接 Binder endpoint 的 `DeadObjectException`，并通过 `PrivilegeBinderCallFailure.ServerUnavailable` 或 `PrivilegeBinderCallFailure.BinderDied` 调用宿主提供的 fallback；其他异常原样传播。
- 每次项目自有 server control 调用捕获完整 `ServerConnection`；调用失败只允许清理仍对应同一连接的全局状态，不持有连接锁执行 IPC，也不能让旧 server 的迟到失败清除替代连接。
- fallback 只表示调用结果无法确认，不提供自动重试语义；非幂等写操作可能已在远端执行，宿主必须自行处理这种不确定结果。

Binder 支持不应覆盖：

- Android framework service 的类型化封装；
- 高级特权操作 facade；
- hidden framework API 的兼容层。
- 系统服务枚举、系统服务领域发现 API 或可复用的类型化系统 Binder facade。

应用可以基于本项目的 Binder 管线定义自己的 Binder 契约。

## UserService 架构

UserService 是应用自定义特权逻辑的扩展机制。

项目负责生命周期管线：

- 声明 UserService 身份；
- 请求 start 或 bind；
- 将客户端 Binder 连接到服务 Binder；
- 观察服务死亡；
- unbind 或 stop；
- 暴露生命周期错误。

当前 UserService 公开原语由 `:priv-core` 的 `priv.kit.core.userservice` package 分区承载，内部 wire contract、AIDL、registry、manager、loader、host、destroyer 和独立 UserService 进程入口由 `priv.kit.core.internal.userservice` 承载：

- `PrivilegeUserServiceSpec` 使用 `serviceClassName + tag` 标识一个应用自定义 UserService 实例；
- `version` 不是实例身份的一部分，只控制同一 `serviceClassName + tag` 是否复用现有实例；
- 当 `serviceClassName + tag` 相同但 `version` 变化时，旧实例会被销毁并由新实例替换；
- `PrivilegeUserServiceSpec.embedded` 控制服务是否嵌入 Privileged Server 进程运行，默认 `false` 会为每个实例启动独立 `app_process` 子进程；
- `embedded = true` 只作为显式 opt-in，用于低风险、短耗时、可接受污染 server 进程的服务；
- `PrivilegeUserServiceSpec.daemon` 控制服务是否以守护模式运行，默认 `false`；非守护模式下，仅 bind 的服务会在最后一个连接关闭后销毁，已 start 的服务会在 owner app 进程死亡时销毁；守护模式会保留服务直到显式 stop 或 server 退出；
- `PrivilegeUserServiceEnvironment.isEmbedded` 在 UserService 内报告当前实例是否嵌入 Privileged Server 进程；server 入口通过进程级 Java System Property 写入角色标记，属性在首次读取后缓存，因此不依赖 UserService 与 server 是否使用同一个 ClassLoader；
- Privileged Server 为 Embedded UserService 延迟创建并缓存同一组 package `Context` 与 `ClassLoader`，所有需要 `Context` 构造器的嵌入式实例复用这两个引用；只有无参构造器的服务不会触发该缓存初始化；
- UserService 类本身必须实现 `IBinder` 或 `IInterface`，常见形式是直接继承应用自己的 AIDL `Stub`；
- UserService 类可以声明无参构造器、单个 `android.content.Context` 构造器，或两个都声明；如果检测到 `Context` 构造器，会优先使用该构造器；
- 应用自己的 AIDL Binder 由 UserService 暴露，项目只做 Binder handoff，不理解业务接口；
- 如果应用 AIDL 定义了 `void destroy() = 16777114;`，项目会在移除实例时调用该预留 transaction 供应用清理资源；一旦 AIDL 中有方法显式指定 id，该接口内所有方法都需要显式指定 id。

Release 构建中，应对所有可能被 Priv Kit 反射调用的 UserService 构造器标注 `androidx.annotation.Keep`。Kotlin 中可以用私有主构造器承接共享状态，再显式声明两个 secondary constructor：`@Keep constructor() : this(context = null)` 和 `@Keep constructor(context: Context) : this(context = context)`。调用方应使用 `MyService::class.java.name` 生成 `serviceClassName`，不要硬编码源码类名字符串。

独立进程模式下，Privileged Server 是控制平面，UserService 子进程是执行平面。server 通过 app 侧 handshake provider claim 子进程的控制 Binder，再向客户端返回 gate Binder。UserService 如果声明 `Context` 构造器，子进程会先创建 package `Context`，再优先尝试 `LoadedApk.makeApplication(true, null)` 得到应用 `Application`；如果该 framework 路径抛错，会记录日志并回退到 package `Context`。非守护模式下 owner app 死亡时，或 server shutdown 时，server 会向 UserService 发出 destroy 请求；复杂服务应在自己的 `destroy()` 中完成资源释放，并在释放完成后自行调用 `System.exit(0)`。

嵌入模式下，server 在自己的进程内用当前 APK classpath 反射创建 UserService 对象；如果服务声明 `Context` 构造器，只传入 package `Context`，不会调用 `makeApplication`，也不会创建或安装应用 `Application`。如果 package `Context` 创建失败且服务同时声明了无参构造器，会回退到无参构造器；只有 context-only 服务会继续报声明错误。该模式只面向轻量、短耗时、低风险逻辑；`destroy()` 可以不实现，如果实现也只应做快速资源释放，不能调用 `System.exit()`。销毁只能移除 registry 记录、关闭 gate Binder 并调用可选的 reserved destroy transaction，不能卸载 class、清理 static/native 状态或阻止服务代码杀死 server 进程。

服务行为由应用负责。本项目不应在 UserService API 中定义可复用特权功能。

## UI 架构

`:priv-ui` 是可选 UI 层。页面部分使用 Compose；此外，它可以提供不创建页面、ViewModel 或 `Activity` 的 UI 启动偏好重放入口。

它可以提供：

- 运行时状态展示；
- 启动方式选择界面；
- 连接和错误展示帮助能力；
- start、stop、reconnect、bind、unbind 等生命周期动作控件。
- 在 UI 管理的前台启动完成 Binder 连接后，持久化一个精确启动 methodId；
- 保存用户是否期望启用特权功能，并在 server 断开但期望仍开启时提供明确的关闭自动恢复入口；
- 在无界面场景先读取该期望状态，再以调用方传入的同一份 `PrivilegeUiConfig` 精确静默重放对应 runtime 启动原语。

它不得提供：

- 系统操作界面；
- 包管理工具；
- 输入工具；
- 设置编辑器；
- app-ops 编辑器；
- 特权自动化控制台。

UI 模块只映射运行时原语，不扩展项目范围。静默重放不做跨方式 fallback，不主动请求权限或外部 Provider 授权，也不产生页面、Snackbar 等用户反馈；失败以空结果返回。methodId 文件只保存 `root`、`adb-wireless`、`adb-tcpip` 或 `external:<providerId>` 中的一个原始字符串，当前端口、超时和 Provider 实例仍由调用方本次传入的配置决定。只有前台启动已经提交该精确方法，并收到同时匹配当前 operation 与当前 `launchCorrelationId` 的 `INITIAL_LAUNCH` 连接且未先取消时才写入 methodId；静默启动、`OWNER_RECONNECT`、已有连接及其他被动连接均不改写 methodId。期望状态文件 `ui-desired-enabled` 只保存 ASCII `1` 或 `0`。`:priv-ui` 的非导出初始化 Provider 在 core runtime 初始化之后、应用 Provider 对外发布之前安装连接监听；每个被接受的 `INITIAL_LAUNCH` 都写入 `1`，包括复制外置 shell 命令启动的 server，而 `OWNER_RECONNECT`、断连、server 死亡和恢复失败保持原值。只有内置 UI 中已确认的停止动作或断连提示卡片的“关闭自动恢复”动作写入 `0`。`startSilentlyIfEnabled(...)` 仅在该值为 `1` 时委托给精确重放入口；底层 `startSilently(...)` 保留不受该值约束的显式调用语义。前台与静默启动共用一个带所有者的进程内互斥门；已受理的前台启动副作用用绑定同一 ViewModel 所有者的可嵌套租约覆盖其完整生命周期，不同 ViewModel 不能共享嵌套计数。权限请求还要绑定实际 Scaffold host，最后一个 host 离开时清空未完成事务，无 host 时拒绝新请求。任一前台租约存在时静默启动直接失败，静默所有者存在时内置 UI 拒绝新的副作用入口。静默 permit 释放会递增完成序号，已有 ViewModel 必须按该序号重新读取 runtime 状态，完成对账后才能恢复入口，从而覆盖静默启动与 UI 初始化交叠及快速完成时的事件合并。多进程应用必须只在一个指定进程初始化并触发 Priv Kit 启动。

静态 TCP 确认、通知权限、局域网权限和外部 Provider 授权均作为 ViewModel 所有协程中的可取消挂起点：界面只提交结果，原协程随后继续，取消则解除监听并释放租约。权限请求仍绑定实际 Scaffold host；最后一个 host 真正离开时以空结果恢复未完成协程，配置变更由同一 ViewModel 的新 host 直接接管。外部 Provider 的授权与启动统一使用 suspend 契约，不再保留前台状态对账兼容路径。

项目页面 UI 策略统一使用 Jetpack Compose。`:priv-ui` 和 `:priv-sample` 都禁止使用传统 Android View UI 逻辑，例如手写 `android.view.*` / `android.widget.*` 视图树、通过 `Activity#setContentView(...)` 装配界面、或新增用于页面界面结构的 XML layout 文件。

Android 通知自定义内容是唯一例外：`:priv-ui` 可以新增仅供 `Notification` 的 `RemoteViews` 使用的 XML layout，用于 notification pairing 等通知内控制。该 layout 不属于应用页面 UI，不得被页面或示例界面 inflate。

## 错误模型

错误应按项目自有关注点分组：

- startup unavailable；
- startup denied；
- startup command failed；
- server bootstrap failed；
- connection timeout；
- protocol mismatch；
- Binder died；
- UserService failed to start；
- UserService disconnected；
- external starter unavailable；
- invalid configuration。

错误应携带可行动的诊断上下文，但不得把某个策略的内部细节泄漏到无关模块。

## 安全模型

安全姿态很简单：本项目帮助一个应用管理自己的特权运行时，应避免发展成通用特权代理。

设计要求：

- 在平台允许时优先做显式应用身份校验；
- 保持服务端入口收窄；
- 让启动参数可审计；
- 不暴露特权操作的全局注册表；
- 不把跨应用访问作为默认能力；
- 将 Shizuku UserService 视为外部启动桥接入口，而不是广泛的第三方能力共享。

## 兼容模型

项目只能在运行时自身需要时包含兼容桥接代码。

允许的兼容区域：

- 服务端或 Binder bootstrap 所需的 hidden-api stub；
- 编译或绑定运行时契约所需的 framework mirror class；
- 项目自有 Binder 协议所需的 AIDL 兼容桥接。

禁止的兼容区域：

- 大范围 Android 系统服务封装；
- 面向下游应用功能的便利兼容 API；
- 类似 AndroidX 的 framework 抽象层。

## 扩展模型

下游应用通过以下方式扩展本项目：

- 定义自己的 Binder 契约；
- 实现自己的 UserService 逻辑；
- 选择启动策略；
- 自行决定自己的代码执行哪些特权操作。

本项目应让这些扩展点可靠，但不应把下游操作吸收到自己的 API 中。

## 验证预期

后续实现应通过以下方式验证：

- 运行时状态转换测试；
- 启动策略契约测试；
- Binder 连接和死亡处理测试；
- UserService 生命周期测试；
- 在可行时检查 sample app 的 Root、ADB、手动 shell 和外部授权流程；
- API 评审时检查项目宪章。

验证必须包含负向检查，确保禁止的高级 API 不会被引入。
