---
description: 使用推荐的 Compose 授权界面，并配置静默启动。
---

# Privilege UI {#privilege-ui}

`priv-ui` 是接入 Priv Kit 时推荐的起点。它通过 Compose 界面展示运行时授权状态、
提供启动入口，并在静默启动时复用最近一次成功的前台启动方式。自定义界面可以直接
使用 `priv-core`。

## 常用 API {#public-entry-points}

- `PrivilegeScaffold` 提供可嵌入 Compose 页面。
- `PrivilegeUiViewModel` 是可继承的 `AndroidViewModel` 控制器。
- `PrivilegeUiConfig` 用于启用启动方式和外部 Provider。
- `PrivilegeUiExternalStartProvider` 用于添加应用提供的外部启动方式。
- `PrivilegeUi.desiredEnabled` 以只读进程级 `StateFlow<Boolean>` 公开持久化的自动恢复
  意图。
- `PrivilegeUi.startSilently(...)` 在自动恢复开启时按上次成功的方式静默启动。
  `ignoreAutomaticRecoverySetting = true` 会显式忽略该设置。
- `PrivilegeUi.prepareExternalShellScript(...)` 同步写入可选的外置脚本并返回其 `File`，无需 UI。

## 权限解决方案入口 {#permission-solutions}

权限受限卡片左侧为**查看解决方案**，右侧为**查看受限权限**。默认按界面语言（英文或
简体中文）打开 [ADB 权限限制解决方案](./startup-tutorial#permission-restrictions)页面。

传入 `onViewPermissionSolutions` 可替换为宿主自己的导航行为：

```kotlin
PrivilegeScaffold(
    viewModel = viewModel,
    onViewPermissionSolutions = {
        navController.navigate("permission-help")
    },
)
```

传入后仅执行该回调，不再打开默认网站。`PrivilegePreviewScaffold` 支持相同回调；
不传或传入 `null` 时使用默认链接。

## 复用进程级配置 {#application-scoped-config}

在进程级顶层属性中只创建一次外部 Provider 和 `PrivilegeUiConfig`，然后把同一个
实例用于前台 ViewModel 和静默启动 API：

```kotlin
val privilegeUiConfig by lazy {
    PrivilegeUiConfig(
        startupModes = listOf(
            PrivilegeUiStartupMode.ROOT,
            PrivilegeUiStartupMode.ADB,
            PrivilegeUiStartupMode.MANUAL_SHELL,
        ),
        externalStartProviders = listOf(shizukuProvider),
    )
}

val serverInfo = PrivilegeUi.startSilently(
    config = privilegeUiConfig,
)
```

顶层属性及其外部 Provider 使用进程安全的 Application 状态，不持有 `Activity`。
Provider ID 用于保存启动方式，应用升级后保持不变。

`startupModes` 是控制授权 Tab 顺序的有序列表，传入重复项会直接报错。存在外部
Provider 时，`EXTERNAL` 会保留列表中的位置；如果列表中没有它，则自动追加到
末尾。没有外部 Provider 时，即使列表包含 `EXTERNAL` 也不会显示 External Tab。

## 固定的手动启动脚本 {#manual-startup-script}

`manualShellScriptPath` 默认为 `null`，UI 直接显示完整 native starter 命令，不写入外置脚本。
其他 Shell 进程可能在执行前修改外置脚本，导致被修改的代码以执行者的权限运行。
仅在应用接受这一风险时显式启用。

显式启用后，Android user 0 会在准备手动命令时使用可用的外部存储写入启动脚本。路径以应用的
`Android/data/<包名>` 目录为基准，包含文件名。建议使用 `priv.sh`：

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    manualShellScriptPath = "priv.sh",
)
```

UI 使用 `Context.getExternalFilesDir(null)?.parentFile` 返回的实际目录写入脚本并创建父目录。
展示和复制的命令使用较短的 `/sdcard` 别名。示例显示：

```shell
adb shell sh /sdcard/Android/data/<包名>/priv.sh
```

脚本放在 `files` 目录同级。

所有非 0 Android 用户（包括工作资料）都会跳过外部存储，直接显示完整 native starter 命令，
并保留 Core 生成的 owner user ID。即使应用能够写入这些用户的外部目录，Shell 也可能无法读取，
因此此时不会生成或使用外置脚本。

UI 初始化时立即显示 sh 命令并允许复制，每个 ViewModel 仅在初始化时后台准备一次脚本，
该任务独立于静默启动。切换 tab、页面恢复和刷新均不会重新准备，准备成功不会改变命令文本。
准备时会比较现有内容，一致则直接复用，文件缺失或内容变化时才原子写入。同一进程内的并发
准备会串行执行，因此应用预写和 UI 初始化不会重复写入相同内容。
应用更新后，先调用 `PrivilegeUi.prepareExternalShellScript(config.manualShellScriptPath)` 或重新打开 UI，
再使用已保存的命令，以便脚本使用最新安装路径。配置的目标文件内容不一致时会被覆盖；
修改路径不会删除旧脚本。

外部存储不可用或文件读写失败时会回退到完整 native starter 命令。
路径必须是相对路径，不能包含空路径段、`.`/`..`、反斜杠
或控制字符，解析后的路径也不能越出应用的外部目录。执行端的 shell 需要能够读取脚本。
此配置仅影响 Android 手动命令的准备，自动启动方式
和多平台预览维持现有行为。
路径使用英文字母、数字、`/`、`.`、`_` 和 `-` 时，复制的命令可直接用于不同桌面 shell；
其他字符会使用 POSIX 宿主 shell 的引号规则。

如果希望无需 UI 即可创建或更新外置脚本，可以直接调用此同步 API，例如在应用初始化时：

```kotlin
val scriptFile: File? = PrivilegeUi.prepareExternalShellScript("priv.sh")
val scriptPath: String? = scriptFile?.absolutePath
```

方法直接接收可空路径，无需配置对象；也可以传入 `privilegeUiConfig.manualShellScriptPath`。
返回系统外部目录下脚本的实际绝对路径 `File`，不会将其路径改写为 `/sdcard`。
路径为 `null` 或 Android userId 非 0 时，跳过 native 命令解析及存储访问，返回 `null`。
外部存储不可用或文件读写失败也返回 `null`；解析 native starter 的异常会传递给调用方。
所有操作都在调用线程同步执行，包括首次使用时的 APK 检查、内容比较，以及需要写入时的文件同步。
调用方自行选择执行上下文，内置 UI 和 sample 在各自的调用点选择 `Dispatchers.IO`。
命令展示和回退由 UI 单独处理。准备脚本不会启动服务，也不依赖自动恢复开关或启用的 Tab。
`startSilently(...)` 仍是独立操作，不会准备脚本。

## 嵌入 Scaffold {#embed-scaffold}

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    privilegeUiConfig,
) {
    override fun onBackClick(): Boolean {
        return true
    }
}

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

Scaffold 会自己注册 Activity Result launcher，并把权限结果交给正在等待结果的
ViewModel。

Server 已连接时，点击内置的 Root、ADB 或外部启动按钮会先显示重新启动确认。
取消不会影响当前 server；继续后，所选启动身份会先结束旧进程，再启动替代进程。
如果该身份无权结束旧进程，本次启动会停止，Scaffold 通过 Snackbar 提示失败。
自定义界面需要监听 `serverRestartConfirmation`，展示自己的确认交互，然后调用
`confirmServerRestart()` 或 `cancelServerRestart()`。发起启动的协程会挂起等待该
结果，随后在原调用上下文中继续所选流程。

## 观察服务端状态 {#server-state}

`PrivilegeScaffold` 已经在内部观察运行时并展示状态。应用的其他功能依赖连接时，
应直接观察进程级的 `Privilege.serverState`，不再通过 UI 专用回调接收状态。

无论 `PrivilegeScaffold` 页面当前是否显示，该状态都持续可用。
[启动方式](./activation#connection-state)分别给出了应用级持续监听和页面级状态
展示的写法。

自定义界面需要展示用户是否仍期望自动恢复时，可以单独观察
`PrivilegeUi.desiredEnabled`：

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

这个只读状态在断连和静默启动失败后仍会保留。当前连接状态由
`Privilege.serverState` 提供。

## ADB 界面流程 {#adb-ui}

`priv-core` 负责配对、发现、授权、TCP/IP 和启动，`priv-ui` 通过
`PrivilegeScaffold` 展示这些操作及其状态。

通过 `PrivilegeUiConfig` 配置由 UI 管理的 ADB 流程：

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

### 无线调试界面 {#wireless-debugging-ui}

选中 ADB 启动方式后，面板会定期检查无线调试和配对状态。它的前台流程
可以：

- 展示配对对话框，并通过 `priv-core` 提交六位配对码；
- 通过可选的通知配对流程接收配对码；
- 在系统要求时请求 `ACCESS_LOCAL_NETWORK`；
- 已保存的 ADB 密钥完成配对后，通过无线调试启动。

通知配对服务是 `priv-ui` 的内部组件，应用通过 `PrivilegeScaffold` 使用它。无法使用
通知输入时，Scaffold 会保留前台配对
对话框，供用户与 Android 设置页配合分屏操作。权限警告会把“无通知继续”、设置中
授权后继续或取消返回给同一个挂起的配对协程。

宿主可以通过 `PrivilegeUiConfig.notificationPairingChannelId` 和
`notificationPairingNotificationId` 预留自己的通知命名空间，默认值分别是
`priv_ui_adb_pairing` 和 `201`。通知 ID 会连续占用两个值：配置值用于前台状态通知，
下一个值用于输入配对码。渠道 ID 保持稳定并专用于此流程；通知 ID 从 `1` 到
`Int.MAX_VALUE - 1` 中选择一个宿主尚未占用的值。`priv-ui` 会按需创建配置的渠道，
但不会删除它。

`priv-core` 可以在具备权限时临时打开和关闭无线调试。`priv-ui` 读取状态并把
对应配置传给 Core，由 Core 负责 `Settings.Global` 变更。

### TCP/IP 界面 {#tcp-ip-ui}

`PrivilegeUiConfig.tcpPort` 指定静态端口。
`PrivilegeUiConfig.adbTcpPolicy` 用于关闭 TCP/IP、优先使用现有静态端口，或在
无线调试完成配对后提供创建静态端口的入口。

通过界面调用 `priv-core` 执行 `adb tcpip` 时，内置 Scaffold 会先展示一次确认。
取消后 ADB 保持不变。自定义界面调用 `PrivilegeUiViewModel.enableTcpMode()` 或
`startStaticTcpAdb()` 时，监听 `staticTcpSwitchConfirmation`，展示自己的
警告，再调用 `confirmStaticTcpSwitch()` 或 `cancelStaticTcpSwitch()`。

UI 可以请求本机 ADB 密钥授权，并在系统返回结果后继续之前的启动操作。仅检查状态
不会修改 ADB 设置。

静默启动要求配对、权限、TCP 授权和静态端口已经就绪，否则返回 `null`，交由前台
流程继续处理。

## 静默启动 {#exact-replay}

前台启动成功并建立 Binder 连接后，UI 会保存一个启动方式 ID：

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

静默启动只会尝试已保存的方式，不会切换到其他方式。已保存的方式、授权或其他启动
条件不可用时返回 `null`，权限请求、配对和外部授权仍需在前台界面中完成。

前台启动和静默启动通过进程内启动门互斥。多进程应用选择一个进程初始化并调用
Priv Kit 的启动 API。静默启动期间，内置界面会暂时禁用其他启动
入口，并在完成后刷新运行时状态。如果 Root 管理器保存的授权已经失效，它仍可能
展示自己的授权界面。

UI 发起的前台启动成功并收到匹配的初始连接后，才会开启自动恢复。UI 前台启动
之外的初始连接（包括执行复制的手动 Shell 命令）保持当前设置。用户确认停止或选择
“关闭自动恢复”时关闭；断连、服务端死亡和静默启动失败保留原设置。
`PrivilegeUi.desiredEnabled` 会独立于当前服务端连接状态发布这项持久化意图。
`startSilently(...)` 默认遵循自动恢复设置；
`ignoreAutomaticRecoverySetting = true` 会显式忽略它。

## 添加 Shizuku {#shizuku}

应用可以通过 `PrivilegeUiStreamingExternalStartProvider` 将 Shizuku 添加为
`priv-ui` 的外部启动方式。该实现需要：

- 通过 `snapshot()` 返回 Shizuku 可用性和权限状态；
- 在 `requestAuthorization()` 中请求权限，并在回调后返回最终状态；
- 在 `start()` 中绑定 Shizuku UserService，并把收到的 `commandLine` 交给
  `PrivilegeExternalStartup.runThroughBridge(...)`；
- 保持 Provider ID 不变，使静默启动在应用升级后仍能找到它。

按照上面的示例，将 Provider 注册到
`PrivilegeUiConfig.externalStartProviders`。[外部启动示例](./activation#external)
说明 Shizuku UserService 的 AIDL、UserService 实现和绑定。sample 提供了完整的
[Privilege UI Provider](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt)。

## 跨平台展示 {#multiplatform-preview}

`PrivilegePreviewScaffold()` 可在 Android、JVM 和 WasmJS 中展示同一套页面。由宿主提供 Material 3 主题，内存模拟数据驱动正常的状态、按钮和弹窗。该入口不初始化运行时，也不申请权限。Android 真实功能继续使用 `PrivilegeScaffold`。

启动、配对、外部授权及确认弹窗默认可交互。操作模拟成功，配对接受任意六位数字。手动页提供示例命令，顶部“启动服务”操作模拟执行该命令。复制按钮使用宿主剪贴板，但不会执行命令或修改系统；关闭宿主后丢弃本次数据。可在 [UI 展示](/zh/playground/) 中体验。

手动命令使用为 `priv.kit.sample` 随机生成的安装路径。设置 `useLegacyPackaging = false` 可展示通过 `linker64` 加载 APK 内原生库的命令，默认值 `true` 展示解压后的原生库路径。展示页在画布外提供该开关；切换格式会保留路径和模拟状态。

通过顶栏的「UI 展示」打开独立的 Vue 展示页。

## 由宿主控制的跨平台页面 {#host-controlled-page}

当状态由宿主管理（例如 Desktop 模拟控制窗口）时，使用 `PrivilegeScreen`。
它接收 `PrivilegeUiScreenState`、`PrivilegeUiActions` 和必填的 `showFeedback` 回调，
在 Android、JVM、WasmJS 上渲染与 Android 和 Preview 入口相同的页面，不创建运行时，也不会自动完成操作。

```kotlin
PrivilegeScreen(
    state = screenState,
    actions = hostActions,
    showFeedback = ::showMessage,
    systemPrompt = prompt, // PrivilegeUiPromptState(title, message)，或 null
    topBar = { AppTopBar() },
)
```

宿主必须显式实现全部操作回调，更新可观察状态，并管理异步任务及取消。
不支持的操作应提供反馈；复制操作应使用宿主剪贴板。`requestBatteryOptimization` 返回
是否打开了请求或设置界面。`interactionEnabled` 控制展示上的可用性，`canInteract` 在点击时
检查当前交互许可，忙碌时仍应允许取消；`busy` 单独阻止冲突的启动操作。

`startupModes` 不能为空；选中模式不在列表中时，展示第一个模式。宿主提供的消息和
`PrivilegeUiPromptState` 文本应已完成本地化。替换连接时递增 `connectionSerial`，
以重置连接相关的展示状态。列表作为快照使用，应发布新状态，不要原地修改列表。

宿主可注入连接失败、配对阶段、权限限制、启动日志、重启/TCP 确认和仅供展示的系统提示。
平台权限请求及提示归属仍由宿主管理；Android 适配层保留原有生命周期和权限处理。
HTTP 控制接口及应用专属模拟模型放在接入应用中。内部页面作用域和组件不属于公共 API。

`systemPromptWindowInsets` 独立于 `contentWindowInsets` 控制提示层的位置，默认使用
`WindowInsets.safeDrawing` 的顶部边距。Desktop 模拟宿主可传入模拟状态栏或挖孔的边距，
例如 `WindowInsets(top = 48.dp)`，不影响页面内容的位置。Android 调用方可保留默认值。

## 用户教程链接 {#startup-tutorial-links}

ADB 设备指纹标题右侧与手动页复制按钮左侧的“使用教程”、配对弹窗的教程入口，以及权限受限的解决方法，
统一打开同一篇[用户指南](./startup-tutorial)的对应章节。
`PrivilegeScaffold`、`PrivilegePreviewScaffold` 和 `PrivilegeScreen` 均支持：

- `startupTutorialUrl`：替换完整教程地址；默认按界面语言选择官网中英文页面。
  URL 中已有的片段会被对应主题的锚点替换，查询参数保留。
- `onViewStartupTutorial`：接收 `PrivilegeUiTutorialTopic`，替换所有默认教程导航。
- 原 `onViewPermissionSolutions`：仅覆盖权限解决入口，优先于统一回调。

例如 `PrivilegeScaffold(startupTutorialUrl = "https://example.com/help/startup")`。
回调存在时只执行回调，不另外打开浏览器。教程入口在操作忙碌时仍可用。

指南使用普通 Markdown 和 HTML 锚点，无需 VitePress 组件。复制对应语言的
`startup-tutorial.md` 即可部署；保留文档中的锚点（包括 `adb`、`pairing`、`manual`、
`tcp-ip` 和 `permission-restrictions`），让 UI 能定位到正确章节。
