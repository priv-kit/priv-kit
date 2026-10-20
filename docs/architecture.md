# Priv Kit 维护架构

本文说明模块职责和运行时设计。接入方法见 [网站指南](../priv-website/zh/guide/getting-started.md)，开发规则见 [AGENTS.md](../AGENTS.md)。

## 范围与 API

每个应用管理自己的特权服务端，支持 Root、ADB、手动命令和外部授权桥。Core 提供连接、Binder、文件、命令和 UserService；系统服务的业务接口由应用实现。

公开包使用 `priv.kit.*`，类型使用 `Privilege*`，Maven group 为 `io.github.priv-kit`。稳定接入范围为 `priv-core`、`priv-ui` 及其 API 依赖。`priv-input` 和 `priv-evdev` 为实验模块；`priv-shared`、`priv-adb-crypto` 和内部协议不承诺直接调用兼容性。

## 模块

| 模块 | 职责与依赖 |
| --- | --- |
| `priv-core` | 运行时、启动、Binder、文件、命令和 UserService；依赖 shared、adb-crypto |
| `priv-shared` | Android/JDK 工具及隐藏 API 兼容 |
| `priv-adb-crypto` | 纯 Kotlin/JVM ADB 加密与配对 |
| `priv-input` | Android 系统输入注入，依赖 shared |
| `priv-evdev` | evdev 查询、写入与触摸手势，包含 JNI，依赖 shared |
| `priv-ui` | Compose 跨平台界面；Android 依赖 Core |
| `priv-sample` | Android 接入示例，包含 legacy 和 api29 打包变体 |
| `priv-playground` | JVM/WasmJS 预览，依赖 UI，不发布 |
| `hidden-api` | 仓库内的编译期 framework 声明，不发布、不进入 APK |

Core、Shared、Input 和 Evdev 通过 `remapApi(:hidden-api)` 获取本模块的重映射输入。Remap 自动生成按隐藏类型全名匹配的 `dontwarn`，并随各模块 AAR 导出 consumer rules，无需单独维护规则文件或运行生成脚本；Remap 还根据隐藏 API 声明与实现类的继承关系生成回调保留规则，使 Binder wrapper 存活时保留 `shellCommand` 的名称和签名，同时允许未使用的 wrapper 被整体裁剪。消费应用无需依赖 hidden-api。Shell 事务通过 Binder 继承读取系统的 `SHELL_COMMAND_TRANSACTION` 字段，避开 R8 的接口字段重命名异常。隐藏 API 的维护版本差异由 Shared 在运行时检测；部分系统缺少的接口通过接收 `IBinder` 的 wrapper 隔离。Shared 不保存长期状态，不引入资源、组件、Compose 或协程。

## 启动与连接

所有入口执行同一 native starter，通过 Binder 握手连接。Starter 在读取 APK、停止旧进程及 fork 前验证 UID，只接受 root（0）、system（1000）和 shell（2000）。Provider 再验证调用 UID，客户端验证身份、协议、classpath 和启动关联。

Android 10 以下使用解压到 `nativeLibraryDir` 的 starter；较新版本也可由系统 linker 加载 APK/ABI split 中未压缩的库。

每个 package/user 有独立进程标识。user 0 使用默认作用域，其他用户显式传入 userId。进程名为 `<package>:priv-server`，非 0 用户追加 `-u<userId>`。发现优先匹配完整 cmdline，受限时使用内部 token 和 UID。

重复启动先扫描和验证旧进程，再检查 signal 权限、结束旧进程并确认退出，最后创建新进程。扫描、权限或退出确认失败时不创建替代进程。协调启动携带 `launchCorrelationId`，公开手动命令可独立执行。

控制、生命周期、文件、命令和 UserService-manager Binder 随握手作为同一快照安装。`PrivilegeServerInfo` 的构造和 copy 为 internal，相等性包含 lifecycle Binder。生命周期 token 在同一服务端内保持身份，无业务 transaction；SELinux context 按进程缓存，仅用于诊断。

一个应用选定一个进程初始化和启动。前台、静默启动及 owner reconnect 共用仲裁器；提交前优先接受现有重连，提交后由当前操作持有结果。初始连接同时匹配 operation 和 `launchCorrelationId`。连接锁内只更新状态，不执行 IPC 或应用回调。跨进程互斥由应用负责。

## Owner 重连

owner 死亡后按 `followDeathDelayMillis` 等待，默认十分钟。通常等待应用进程启动信号；`activeReconnectOnOwnerDeath` 可启用主动 Provider 重试。设置更新用于下一次死亡，进行中的重连保留原快照。

六十秒内三次 owner 死亡会停止主动重试，降为被动等待；手动启动仍可接管，owner 稳定存活六十秒后恢复主动能力。

`prepareOwnerRestart()` 登记一次性计划，只匹配五秒内当前 owner 的死亡。命中后按指定时间被动等待且不计入 crash-loop；超时后在原 follow-death 截止时间内恢复既定策略。进程观察不可用时，轮询排除通知者的旧 PID。

## Binder 与权限

Binder wrapper 转发原始 transaction，保留目标 Binder 的错误。Core 控制调用及 wrapper 转发前的连接获取统一使用 `PrivilegeServerUnavailableException` 表示服务端不可用。`PrivilegeBinderCall.orElse` 将它分类为 `ServerUnavailable`，将原始 `DeadObjectException` 分类为 `BinderDied`；后者本身不能确定死亡的是服务端还是转发目标。回复丢失时副作用可能已经发生，应用按幂等性决定是否重试。

`checkPermission`、`grantRuntimePermission`、`revokeRuntimePermission` 直接调用 framework。`getDeniedServerPermissions()` 在服务端枚举 UID 关联包、检查声明权限并过滤未定义权限；在服务端查询可避免客户端包可见性造成的遗漏。访问结果还受 AppOps、SELinux 和服务内部策略影响。

PID/UID 权限检查通过 Shared 直接调用 ActivityManager，绕过 Context 缓存。客户端的 `checkServerPermission()` 和 `isPermissionRestricted()` 使用同一连接快照并在查询前后检查有效性；ActivityManager 异常本身不使服务端断线。启动预检使用握手身份，实际授权仍在服务端执行。按包查询和系统服务发现也留在服务端，以保留调用身份和可见性。

## 文件与命令

`PrivilegeFile` 在客户端计算路径属性，服务端执行文件系统操作。同名 Boolean 方法沿用 `java.io.File` 语义；`replaceAtomically()` 使用同一文件系统的 `Os.rename`，异常 cause 保留 errno。

目标 fd 留在服务端，内容通过 reliable pipe 传输。输入流在 EOF 检查错误；输出流关闭等待消费和目标关闭，`syncOnClose` 额外执行 fsync。最多四个内容传输，无等待队列。

`walk()` 是无排序、弱一致、深度优先先序冷流。根目录不输出，直接子项深度为 1。basename glob 支持区分大小写的 `*`、`?` 和反斜杠转义，命中目录仍输出但不进入。lstat 返回 EACCES/EPERM 时保留名称和空元数据；符号链接和无元数据项不进入。后代遍历使用 `SecureDirectoryStream`。最多四个 walk，首项立即刷新，后续默认每 32 项刷新。

`deleteRecursively()` 规范化路径并拒绝根目录，通过描述符相对操作先删子项再删父目录。目标不存在或全部删除返回 true，剩余条目返回 false；取消或失败不回滚已删除内容。

`startCommand()` 将 argv 直接交给 `ProcessBuilder`，不添加 shell。启动成功后返回一次性句柄，只能选择 `stream()` 或 `awaitResult()`。两者同时排空 stdout/stderr；流式输出的字节块不保证行或字符边界，退出事件在进程结束及两条 pipe EOF 后发出。

命令控制使用 Bundle、IBinder、ResultReceiver，输出使用两条 reliable pipe。最多四个命令，无等待队列；总超时从进程启动计算。取消、owner 死亡和服务端关闭会终止进程。无 stdin、PTY 或 daemon 管理。

## UserService

应用定义 AIDL 和实现，Core 管理启动、绑定、停止及 Binder 交付。实例由 `serviceClassName + tag` 标识，version 变化控制替换。

生命周期操作通过 operation id、异步回调和接收确认完成。Binder 线程只提交任务，执行器和配额限制并发，固定条带锁按服务串行化。取消回收未接收的进程或连接；unbind 幂等，在不可取消上下文完成。

嵌入式服务只清理自己的资源，独立服务可在销毁后退出。反射构造函数需要保留；应用 AIDL 留在应用模块。

## 输入

`priv-input` 与 `priv-evdev` 互不依赖，均不依赖 Core。`PrivilegeInput` 接收系统 input 代理 Binder，在本地生成 AOSP 输入事件并调用 `injectInputEvent`；`PrivilegeEvdevController` 在特权进程持有 evdev fd，通过应用 UserService 交付设备会话 Binder。公开参数和使用说明见[输入指南](../priv-website/zh/guide/input.md)。

| 行为 | 版本差异 |
| --- | --- |
| keyevent 默认 source | Android 8–9 为 KEYBOARD，10+ 为 UNKNOWN |
| 单独 longPress | Android 8–12 立即发送重复事件；12 使用 global action timeout 偏移重复事件时间；13+ 等待 long press timeout |
| 按键 UP 时间 | Android 8–15 保留初始时间，16+ 使用当前时间 |
| 滑动 | Android 8–9 的 downTime 等于各事件时间，10+ 保留初始 downTime；16+ 按 AOSP 的 120 Hz 节奏发送 |
| 拖动时长 | Android 8–9 不含长按等待，10+ 包含长按等待 |
| 触摸工具类型 | Android 8–11 为 UNKNOWN，12+ 按 source 设置 |
| 默认组合键 | Android 12 及其低版本补齐使用 tap timeout、不合成修饰键；13+ 默认时长为 0、按顺序更新修饰键 |

版本分支对照 AOSP Android 8.0、8.1、9–17。新增选项采用最早支持的实现补齐：12 的双击和默认组合键、13 的定时组合键、14 的滚动/异步/按键间隔、15 的按键持续时间。Android 14 维护版本的命令解析差异由本地参数处理统一覆盖。组合键释放保持目标 displayId，避免 AOSP 部分版本新建 UP 时丢失屏幕。指定其他屏幕需要 Android 10+。

显式 `shellCommand(vararg args)` 在 Android 12+ 通过 Shared 的事务编码调用设备 input 服务，原样传递参数，合并收集 stdout/stderr 并检查结果。`compatShellCommand` 解析已知命令，在 Android 12+ 按实例缓存设备 help 并检查命令及选项，设备支持时转发；缺失功能及 Android 8–11 复用便捷方法本地补齐。未知扩展交给设备解析，低版本报不支持。两者为同步入口，执行前选择路径，失败不重放。

便捷方法为 suspend，Binder 注入放在 IO 调度器，每次注入前检查 Job。clear/restoreCallingIdentity 仅包围一次同步事务，等待使用 delay。取消或失败时在 NonCancellable 中释放已按下的键或发送触摸 CANCEL；DeadObjectException 直接抛出并跳过清理。

Evdev 每次手势从服务端取得独立取消 Binder，再执行同步 touch 事务。取消 Binder 只设置标记，不占用设备锁；服务端在事件和等待间检查标记并释放触点。调用方 cancelAndJoin 等待在途事务结束，取消前尚未开始的任务不写入事件。取消不关闭设备，后续手势可继续使用。

`priv-input` 包含 `internal`（参数校验与按版本生成事件）；`priv-evdev` 包含 `internal`（设备会话、JNI、关联查询与监听）、AIDL 和 C++。公开类型分别位于 `priv.kit.input` 和 `priv.kit.evdev`，evdev 原生库为 `libprivkitinput.so`。只依赖 `priv-input` 的应用不携带 evdev 原生库。

设备会话固定首次接入 owner 的 UID/PID，并监听 death。注册表锁管理句柄与 owner，各句柄的锁串行化操作和清理。内部 AIDL 使用 @Parcelize 结果和统一错误结构；本地调用保留原始异常，远程端点编码业务错误，Binder 传输异常独立传播。

JNI 按 ABI 构造 input_event，每批执行一次非阻塞 write，报告部分写入或 errno。手势在持有 fd 的进程一次执行，识别 Type B、Type A 或单点协议并生成触点和同步帧；不调用 InputManager。JNI 使用 Android 默认混淆配置中的 native 方法规则，隐藏 API 声明只进入编译及 R8 分析。原生库支持四种 Android ABI 和 16 KiB 页对齐。

设备关联通过 input 服务 dump 连接 EventHub 节点与 InputReader 设备，使用匿名管道并行读取。仅在全部相关触摸 mapper 的直接触摸 Viewport 明确且一致时返回 displayId；Android 14+ 还需与 getAssociatedDisplayId 一致。未知关联返回 null。

显示几何通过 Display.getDisplayInfo 一次读取。固定节点句柄每次手势刷新几何；configureTouch 改为手动映射。按 displayId 打开的句柄共用 InputDeviceListener、DisplayListener 和 HandlerThread，回调只递增配置版本，查询按版本缓存。Android 16+ 显式订阅屏幕状态事件。下次手势重新匹配，仅在节点路径、输入设备编号或相关设备版本改变时重开 fd；查询、打开期间版本变化则拒绝结果。关联缺失或歧义会失效缓存，允许下次显式调用重新查询。

手势执行中检查所选输入设备和目标屏幕的存在状态、几何及亮灭状态；无关通知只使关联缓存失效，不取消手势。失败时尝试释放触点，不重放事件。控制器关闭或 owner 死亡标记取消，等待在途操作退出后关闭 fd、注销监听并退出线程。失败的监听清理保留显式 close 重试能力，已关闭的 fd 不会重复关闭；关闭中的会话禁止普通操作。

## UI 与恢复

`commonMain` 保存布局、展示状态和回调，`androidMain` 适配 Core、权限和生命周期。`PrivilegeScaffold` 用于 Android；`PrivilegeScreen` 由宿主提供状态和全部操作；`PrivilegePreviewScaffold` 用内存模拟驱动相同页面。

中英文字符串以 `commonMain/composeResources` 为源，生成 Compose 资源和 Android ID；通知保留同步解析和 RemoteViews XML。

被动检查仅在有 resumed 宿主且选中对应 Tab 时运行。离开或切换时取消，返回立即刷新；恢复和焦点信号合并，同类并发检查共享任务。主动启动及通知配对独立运行。配对会话持有任务、端口、代次和交互许可。

Android 17 缺本地网络权限时，ADB 页显示授权卡片；点击申请，永久拒绝时引导设置。被动无线发现暂停，但不拦截显式 ADB、静态 TCP 或静默启动。授权后取消并等待进行中的发现任务结束，再重新发现和刷新；不重放启动或中断已有连接。

静默恢复仅使用 UI 最近一次成功的启动方式和现有授权，不跨方式回退。匹配 UI 操作及启动关联的初始连接保存 methodId 并开启恢复；其他连接不改变设置。断连和失败保留设置，确认停止或关闭恢复会清除。`desiredEnabled` 发布该设置。

已连接时再次启动需确认替换。原 ViewModel 协程挂起等待交互结果；ViewModel 或最后一个宿主离开时取消待决交互。前台和静默启动共用无排队、无抢占的互斥锁。

## 文档与构建工具

Gradle 管理产品模块和 JVM/WasmJS；pnpm 管理网站及浏览器构建。外部 npm 依赖集中在根 devDependencies，通过 workspace catalog 维护版本；内部依赖使用 workspace 链接。具体规则见 AGENTS.md。

`priv-website` 是 VitePress 源目录，英文页面在 `zh` 下有对应中文页。使用默认主题，Tailwind 不加载 Preflight；维护文档放在 docs。

`priv-playground` 构建 Wasm、Skiko 和 Compose 资源到忽略的 dist，作为私有 workspace 包导入网站。Vue 负责挂载、卸载和配置更新；主题变化保留会话，语言变化开启新会话。资源通过静态 URL 交给 Vite 处理，Wasm 大小清单用于下载进度。

浏览器字体裁剪保留 ASCII 及未忽略的 Kotlin/XML 源文件字符，包含未跟踪文件，不解析转义；原字体保持完整。工具源码和字体说明见 [playground README](../priv-playground/README.md)。

网站构建统一调度 Wasm 和 VitePress，避免重复构建。website workflow 使用 Wrangler 部署到 `https://priv-kit.pages.dev`，凭据为 `CLOUDFLARE_API_TOKEN` 和 `CLOUDFLARE_ACCOUNT_ID`。

Maven 发布白名单包含 core、shared、adb-crypto、ui 和 input；sample、playground、hidden-api 不发布。

## 验证

Android 混淆验证：

```shell
./gradlew :priv-sample:assembleLegacyRelease :priv-sample:assembleApi29Release
```

在 priv-website 目录验证文档：

```shell
pnpm check
pnpm build
```
