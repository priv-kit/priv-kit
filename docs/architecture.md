# Priv Kit 维护架构

本文记录长期稳定的设计边界。API 用法见官网和源码，实现细节以代码为准。

## 项目定位

Priv Kit 是单应用自管理的 Privileged Runtime，负责启动、连接和管理应用自己的
Privileged Server。公开能力集中在以下范围：

- Root、ADB、手动命令和外部授权桥启动
- 服务端生命周期、连接、重连和状态观察
- 显式 Binder 端点与系统服务名的 raw transaction
- 绝对路径上的基础文件操作和目录遍历
- 非交互式命令的启动、流式输出与有界结果收集
- 应用自定义 UserService 的生命周期与 Binder handoff
- 围绕这些能力的可选 Compose UI

包管理、输入、设置、app-ops、设备自动化和其他领域功能由接入应用或下游库基于
Binder、文件代理或 UserService 实现。运行时按“一个应用拥有一个服务端”设计，设备级
共享守护进程、多租户注册和通用权限代理不属于项目范围。

## 命名和 API 承诺

固定标识如下：

- GitHub Organization 和 Repository：`priv-kit`
- 对外名称：`Priv Kit`
- Maven `groupId`：`io.github.priv-kit`

Framework mirror 与 stub 位于 `:hidden-api`，其余源码 package 使用 `priv.kit.*`。
公开类型使用完整的 `Privilege*` 命名。

兼容性承诺覆盖 `io.github.priv-kit:priv-core`、`io.github.priv-kit:priv-ui` 及其通过
`api(...)` 暴露的类型。`priv-shared`、`priv-adb-crypto`、`priv.kit.core.internal.*`、
反射入口、ContentProvider 和内部 AIDL 类型都是实现细节。

## 模块和依赖方向

| 模块 | 发布名称 | 职责 |
| --- | --- | --- |
| `:priv-shared` | `priv-shared` | Android/JDK 底层原语、不变量和 hidden API 兼容 |
| `:priv-core` | `priv-core` | Runtime、启动、server、Binder、文件代理、命令和 UserService |
| `:priv-adb-crypto` | `priv-adb-crypto` | ADB 证书和 Wireless Debugging pairing 加密 |
| `:priv-ui` | `priv-ui` | Android/JVM/WasmJS 共享页面与 Android 生命周期、恢复 |
| `:priv-playground` | 不发布 | JVM 桌面与 WasmJS 浏览器展示宿主 |
| `:priv-sample` | 不发布 | 公开能力示例 |
| `:hidden-api` | 不发布 | 编译期 framework mirror 和 stub |

```text
:priv-core
    -> implementation(:priv-shared)
    -> implementation(:priv-adb-crypto)
    -> compileOnly(:hidden-api)

:priv-ui (androidMain only)
    -> api(:priv-core)
    -> implementation(:priv-shared)

:priv-playground
    -> implementation(:priv-ui)

:priv-shared
    -> compileOnly(:hidden-api)

:priv-sample
    -> implementation(:priv-core)
    -> implementation(:priv-ui)
```

`:priv-shared` 保存无领域状态机制、窄 Android/JDK 原语和 hidden API 兼容逻辑。Android
维护版本之间的签名分派放在这里；仅部分版本存在的隐藏接口由接收 `IBinder` 的 compat
wrapper 隔离。该模块保持无资源、无组件、无长期可变状态，也不引入 AndroidX、Compose
或协程。

`:priv-adb-crypto` 保持为纯 Kotlin/JVM 的最小 ADB 加密实现。`:priv-ui` 编排 Core
原语，Core 维持对 UI 的单向独立。`:priv-sample` 展示发布模块能力，并从同一源码树
构建 API 26 legacy 和 API 29 modern packaging 两个 flavor。`:hidden-api` 只参与编译。

## 运行时闭环

所有启动入口汇合到同一条 Binder handoff。接受连接前，运行时验证服务端身份、协议、
classpath 和本次启动关联。

运行时保持这些不变量：

- 一个应用指定一个进程初始化并触发启动。
- 前台启动、静默启动和 owner reconnect 使用同一进程内仲裁器。
- 启动提交前优先接收已存在的 owner reconnect；提交后由当前启动操作持有结果。
- 初始连接同时匹配 operation 和 `launchCorrelationId`。
- 连接锁内只更新状态，不执行可能回调应用或发起 IPC 的工作。
- Binder 死亡、连接失败和恢复失败进入可观察状态。
- 主动 owner reconnect 在六十秒内观察到三次 owner 死亡后熔断并降级为被动重连；手动
  启动仍可接管原服务端，owner 稳定存活六十秒后恢复主动能力。
- 应用主动重启前可设置一次性 owner restart 计划。计划只匹配通知后五秒内当前 owner
  Binder 的死亡；命中后先在调用方指定的期限内被动等待，不计入 crash-loop，超时则在
  原 `followDeathDelayMillis` 总截止时间内恢复既定的主动重连策略。进程观察不可用时，
  被动轮询排除发出计划通知的旧 owner PID，只对新的应用进程尝试连接。

多进程应用自行保证跨进程互斥。

## 启动入口

Root、ADB、手动命令和外部授权桥执行同一个 native starter。Core 保存 transport、日志
和诊断，第三方绑定与应用 AIDL 留在接入应用、可选集成或 sample。

Android 10 以下版本使用 `nativeLibraryDir` 中的解压 starter。Android 10 及以上根据
安装元数据选择解压文件，或通过系统 linker 执行 APK/ABI split 中未压缩的 starter。

Starter 在读取 APK、结束旧进程和 fork 前校验实际 UID，支持 root（0）、system
（1000）和 shell（2000）。Provider 再按调用方 UID 验证 handoff。

Owner user 0 使用默认作用域；非 0 user 通过环境变量显式传入。服务端可读名称分别为
`<package>:priv-server` 和 `<package>:priv-server-u<userId>`。进程发现优先匹配完整
`cmdline`，读取受限时匹配内部 package/user token 对应的 `comm` 并核对 UID。每个
Android user 使用独立作用域。

重复执行 starter 采用 kill-first：先完成 `/proc` 快照、身份和 signal 权限预检，再
结束已验证的旧服务端，确认其从 `/proc` 消失后创建替代进程。扫描、权限或退出确认失败
时返回稳定错误和非零退出码。应用进程存活时通过 Binder death 与新握手观察替换；应用
进程退出后，新服务端在对应 user 作用域等待 owner reconnect。

协调启动生成非空 `launchCorrelationId` 并写入命令，公开手动命令保持可独立执行。

服务端按自己的 uid、pid、package、协议和启动关联报告身份。SELinux context 在每个
服务端进程中读取一次并缓存，读取失败记录为空值。它只进入诊断信息。

## Binder 原语

Binder 层包含连接与 death 观察、服务端生命周期 token、显式端点和系统服务名的 raw
transaction、内部控制契约以及文件代理契约。Android 系统服务的领域接口和策略位于接入
应用。

Server lifecycle Binder 是独立的跨进程 death token，没有业务 transaction。它在同一
服务端进程内保持 identity，服务端替换后生成新 token。控制、lifecycle、文件代理、命令
执行器和 UserService-manager Binder 在同一次 handshake 中组成一个不可变快照，客户端按
快照整体安装。

`PrivilegeServerInfo` 由 Core 根据已验证的 handshake 构造。它使用
`@ConsistentCopyVisibility` data class，主构造函数和 `copy` 为 internal；结构化相等性
包含 lifecycle Binder。

Package permission 相关公开方法是 `checkPermission`、`grantRuntimePermission` 和
`revokeRuntimePermission` 三个 framework pass-through。`getDeniedServerPermissions` 在服务端
枚举其 UID 关联包声明的权限，并返回按服务端 PID/UID 检查为 denied 的权限名；结果不包含
AppOps、SELinux 或系统服务内部策略。权限策略和更高层流程由应用定义。

Fallback 保留远端结果的不确定性。具有副作用的调用在连接中断后由应用根据幂等性决定
恢复方式。

## 文件代理

文件代理通过独立 Binder 端点随 handshake 交付，在 Privileged Server 进程内执行。
公开入口 `Privilege.file(absolutePath)` 返回组合式 `PrivilegeFile`。

客户端计算路径组合、名称、父路径和隐藏状态；服务端处理存在性、类型、权限、元数据、
创建、删除、重命名和内容访问。与 `java.io.File` 同名的 Boolean 方法保持其返回语义。
服务端缺失或死亡使用 `PrivilegeServerUnavailableException`。`replaceAtomically` 直接执行
同一挂载文件系统内的 `Os.rename`，并通过异常 cause 保留 errno。

特权目标文件描述符留在服务端，内容通过 `ParcelFileDescriptor` reliable pipe 传输。
输入流在 EOF 检查服务端错误；输出流关闭时等待服务端消费、关闭目标并返回结果。
`syncOnClose` 在完成前执行 `fsync`。传输使用最多四个活动任务、无等待队列的独立执行器。

`walk(maxDepth)` 返回无排序、深度优先先序、弱一致的冷流。接收目录不输出，直接子项
深度为 1，默认深度为 `Int.MAX_VALUE`。可选的目录名 glob 在服务端对完整 basename 做
区分大小写的匹配，支持 `*`、`?` 和反斜杠转义；命中的目录仍输出但不进入。动态剪枝和
业务遍历策略由应用或 UserService 实现。服务端通过 `lstat` 读取元数据；`EACCES` 或
`EPERM` 时保留名称并返回空元数据，符号链接和无元数据条目都不进入。后代目录使用
`SecureDirectoryStream` 的描述符相对操作。每次 walk 占一个服务端槽位，最多同时四个。

`deleteRecursively()` 在客户端按词法合并分隔符及 `.`、`..`，并拒绝文件系统根。服务端
通过 `SecureDirectoryStream` 先删除子项，再删除父目录。取消和服务端关闭会停止任务，
已经删除的条目保持现状。目标不存在或全部删除返回 `true`，仍有条目返回 `false`。该 API
接收应用明确选择的单一路径，存储清理和保留策略由应用决定。

## UserService 管线

应用定义 UserService 的 AIDL 和实现，Core 管理 identity、start、bind、unbind、stop、
进程状态和 Binder handoff。实例由 `serviceClassName + tag` 标识，`version` 表达替换语义。

生命周期方法使用 operation id、异步回调和接收确认。Binder 线程只提交工作；有界执行器
和配额覆盖异步操作及取消清理。按服务串行化采用固定条带锁。取消会移除待处理状态，并
回收尚未被客户端接收的进程或连接。连接 unbind 保持幂等并在不可取消上下文完成。

嵌入式实例清理自己的资源，独立进程实例可在销毁后退出。反射入口和构造函数保留可见性
与混淆规则，应用业务 AIDL 留在应用模块。

## 命令执行

命令执行器是随 handshake 交付的独立 Binder 端点。公开入口
`Privilege.startCommand(...)` 在服务端成功创建非交互式 `Process` 后返回一次性
`PrivilegeCommandProcess` 句柄。命令参数按 argv 直接交给 `ProcessBuilder`，Core 不隐式
增加 shell；需要 shell 语义时由调用方明确传入 `/system/bin/sh -c`。

句柄只允许选择一次输出消费方式。`stream()` 合并两条并发读取链路，按读取结果发出
stdout、stderr 字节块，并在进程退出且两条管道 EOF 后发出最终退出事件；
`awaitResult()` 同时排空两条管道并分别保留有界结果。字节块不承诺文本、行或 UTF-8
字符边界，单条流保持顺序，两个流之间没有全局时序承诺。

IPC 请求与控制只使用 `Bundle`、`IBinder`、`ResultReceiver` 和基础值；stdout、stderr
分别通过 reliable `ParcelFileDescriptor` pipe 传输。服务端为每个进程同时运行两条输出
pump，客户端消费时也同时读取两条 pipe，避免任一有限缓冲区写满造成死锁。最多四个命令
并发执行，无等待队列。总超时从进程成功启动后计算，不因输出刷新而重置；取消、owner
死亡和服务端关闭会终止进程并关闭两条输出链路。

该能力不接受 stdin，不提供 PTY、终端尺寸、信号快捷操作、ANSI 模拟、交互式 shell 或
后台 daemon 管理。输出是否及时刷新仍由目标进程决定；针对 pipe 自行缓存的程序可能在
退出前批量输出。

## UI 和恢复

页面 UI 使用 Compose Multiplatform，布局、展示状态和操作回调位于 `:priv-ui/commonMain`。
`androidMain` 将 Core 与 ViewModel 状态映射为纯展示数据，保留权限、生命周期和恢复处理。
`PrivilegeScaffold` 保持 Android 接入方式；`PrivilegePreviewScaffold` 在三端共用布局，
统一由 `PrivilegeUiSimulation` 内存状态驱动，复用相同页面、文案、配对和确认弹窗，
不再维护静态禁用预览分支。Root、无线 ADB、静态
端口与外部授权在可取消的延时后默认成功；手动页按会话生成随机安装路径，包名固定为
`priv.kit.sample`，通过 `useLegacyPackaging` 切换解压库与 APK 内库的命令格式，顶部启动操作模拟执行。
模拟不创建运行时、网络请求或系统权限操作；复制按钮只复制示例文本，宿主销毁会取消任务。
中英文字符串以 `commonMain/composeResources` 为唯一源，同时生成 Compose 资源与 Android
资源 ID，保留通知的同步解析路径。Notification pairing 的 `RemoteViews` XML 只用于通知。

`:priv-playground` 是不发布的 JVM/WasmJS 展示工具。桌面入口创建窗口，浏览器入口导出
接收容器的挂载函数；`priv-website/playground` 提供 Vue + Tailwind 展示组件，在客户端按需加载，
离开页面或切换语言时清理容器。主题切换通过挂载函数返回的更新回调保留当前模拟状态。
它的页面专属样式不进入 VitePress 文档页面。
网站构建先通过 TypeScript 工具调用 Gradle，然后收集 Wasm、Skiko 和 Compose 资源到
忽略的 `priv-playground/dist`，作为私有 workspace 包由页面 `import('priv-playground')`。
包入口将 Compose 资源映射为静态 `new URL(..., import.meta.url)`，交给 Vite 处理资源路径和哈希。
工具源码位于网站，不进入产品模块。

静默恢复重放 UI 最近一次成功确认的精确启动方式。它使用现有授权，不发起新的用户交互，
也不跨方式 fallback。匹配当前 UI operation 与 `launchCorrelationId` 的初始连接会保存
methodId 并开启恢复意图；其他初始连接和 owner reconnect 只更新 Core 状态。断连、服务端
死亡和恢复失败保留用户意图，确认停止或关闭自动恢复时清除。UI 通过只读 `StateFlow`
发布这项意图。

已连接时再次启动会先显示 replacement 确认。需要用户决定的前台流程挂起原 ViewModel
协程，Compose 负责展示请求并返回结果。ViewModel 或最后一个实际 host 离开时取消待决
交互并释放启动门。前台和静默启动共享一个无排队、无抢占的进程内互斥门。

## 源码和仓库工具

Gradle 产品模块使用 Kotlin。Java 保留给 hidden API stub、framework mirror 和 AIDL
兼容桥接；构建脚本使用 Kotlin DSL。

Node.js、TypeScript 和 SVG 用于文档、仓库检查和 CI。可执行工具源码使用 `.ts`。

仓库根目录同时是 Gradle 项目和 pnpm workspace。Gradle 管理 Android/JVM/WasmJS 模块；
pnpm workspace 包含 `priv-website` 和仅用于浏览器构建的私有 `priv-playground` 包。
`priv-website` 不属于 Gradle 模块；`priv-playground` 的 Kotlin 源码仍由 Gradle 编译。根目录的 `package.json`
统一声明 Node.js 与 pnpm 版本，`pnpm-workspace.yaml` 管理包列表和依赖 catalog，
`pnpm-lock.yaml` 锁定依赖。在根目录执行 `pnpm install --frozen-lockfile` 安装依赖，
`pnpm dev`、`pnpm check`、`pnpm build` 和 `pnpm preview` 分别用于站点开发、检查、
构建和预览；产品和展示宿主构建继续使用 Gradle Wrapper。

Maven 发布使用 `publishedModuleNames` 白名单，仅包含 `priv-shared`、`priv-core`、
`priv-adb-crypto`、`priv-ui`；新增模块默认不发布。

VitePress 根目录和公开源目录都是 `priv-website`。每个英文 Markdown 页面在 `priv-website/zh`
有路径等价的简体中文页。维护文档位于 `docs`。文档页面使用 VitePress 默认主题。
`/playground/` 和 `/zh/playground/` 使用薄 Markdown 路由入口，通过 `layout: false` 和
`ClientOnly` 挂载共享 Vue 组件。路径决定展示语言，直接访问时不按浏览器偏好重定向，
语言切换沿用网站的偏好记录，外观共用网站主题状态；离开页面时恢复浏览器语言设置。
Tailwind 接入网站主 Vite 配置，所有页面共用 `priv-website/tailwind.css`，不加载 Preflight。
展示组件的局部样式不覆盖文档主题。Gradle 先编译 Wasm 包，然后由 VitePress 统一构建
所有页面及资源到 `.vitepress/dist`，不再使用独立 Vite 构建、开发端口或 public 中转目录。

`.github/workflows/website.yml` 通过 `pnpm dlx` 运行 Wrangler CLI 部署到
`https://priv-kit.pages.dev`，使用 `CLOUDFLARE_API_TOKEN` 和
`CLOUDFLARE_ACCOUNT_ID` 两个仓库 Secret。

## 变更门禁

公开 API、模块和示例围绕运行时、启动、Binder、文件代理与 UserService 演进。评审时确认：

1. 能力是否属于上述范围。
2. 领域逻辑是否更适合由应用基于 Binder 或 UserService 实现。
3. 命名是否暗示项目提供高层系统服务封装。
4. 代码、依赖和公开类型是否位于对应模块。
5. 公开类型是否使用 `Privilege*` 和 `priv.kit.*`。

边界调整与实现、公开文档和验证一起提交。

## 验证

```shell
./gradlew publishToMavenLocal
./gradlew :priv-sample:assembleRelease
```

在 `priv-website` 目录执行：

```shell
pnpm check
pnpm build
```
