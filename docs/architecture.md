# Priv Kit 维护架构

本文记录长期稳定的设计边界。API 用法见官网和源码，实现细节以代码为准。

## 项目定位

Priv Kit 是单应用自管理的 Privileged Runtime，负责启动、连接和管理应用自己的
Privileged Server。公开能力集中在以下范围：

- Root、ADB、手动命令和外部授权桥启动
- 服务端生命周期、连接、重连和状态观察
- 显式 Binder 端点与系统服务名的 raw transaction
- 绝对路径上的基础文件操作和目录遍历
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
| `:priv-core` | `priv-core` | Runtime、启动、server、Binder、文件代理和 UserService |
| `:priv-adb-crypto` | `priv-adb-crypto` | ADB 证书和 Wireless Debugging pairing 加密 |
| `:priv-ui` | `priv-ui` | Compose 生命周期 UI 和精确静默恢复 |
| `:priv-sample` | 不发布 | 公开能力示例 |
| `:hidden-api` | 不发布 | 编译期 framework mirror 和 stub |

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
服务端进程内保持 identity，服务端替换后生成新 token。控制、lifecycle、文件代理和
UserService-manager Binder 在同一次 handshake 中组成一个不可变快照，客户端按快照整体
安装。

`PrivilegeServerInfo` 由 Core 根据已验证的 handshake 构造。它使用
`@ConsistentCopyVisibility` data class，主构造函数和 `copy` 为 internal；结构化相等性
包含 lifecycle Binder。

Package permission 相关公开方法是 `checkPermission`、`grantRuntimePermission` 和
`revokeRuntimePermission` 三个 framework pass-through。权限策略和更高层流程由应用定义。

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
深度为 1，默认深度为 `Int.MAX_VALUE`。服务端通过 `lstat` 读取元数据；`EACCES` 或
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

## UI 和恢复

页面 UI 使用 Jetpack Compose。Notification pairing 的 `RemoteViews` XML 只用于通知。

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

VitePress 根目录和公开源目录都是 `website`。每个英文 Markdown 页面在 `website/zh`
有路径等价的简体中文页。维护文档位于 `docs`。站点使用 VitePress 默认主题。

`.github/workflows/website.yml` 通过 Cloudflare Wrangler Action 部署到
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

在 `website` 目录执行：

```shell
pnpm check
pnpm build
```
