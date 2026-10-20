---
description: 接入 Compose 授权页面并配置自动恢复。
---

# Privilege UI {#privilege-ui}

`priv-ui` 提供 Compose 授权页面，并可按上次成功的方式恢复服务。自建界面可使用 `priv-core`。

## 常用 API {#public-entry-points}

- `PrivilegeScaffold` 提供可嵌入 Compose 页面。
- `PrivilegeUiViewModel` 是可继承的 `AndroidViewModel` 控制器。
- `PrivilegeUiConfig` 用于启用启动方式和外部 Provider。
- `PrivilegeUiExternalStartProvider` 用于添加应用提供的外部启动方式。
- `PrivilegeUi.desiredEnabled` 以只读进程级 `StateFlow<Boolean>` 公开保存的自动恢复设置。
- `PrivilegeUi.startSilently(...)` 在自动恢复开启时按上次成功的方式静默启动。
  `ignoreAutomaticRecoverySetting = true` 会显式忽略该设置。
- `PrivilegeUi.prepareExternalShellScript(...)` 同步写入可选的外置脚本并返回其 `File`，无需 UI。

## 权限解决方案入口 {#permission-solutions}

**查看解决方案**打开对应语言的[权限指南](./startup-tutorial#permission-restrictions)，**查看受限权限**展示被拒绝的权限列表。

传入 `onViewPermissionSolutions` 可替换为宿主自己的导航行为：

```kotlin
PrivilegeScaffold(
    viewModel = viewModel,
    onViewPermissionSolutions = {
        navController.navigate("permission-help")
    },
)
```

传入的回调替代默认网站导航。`PrivilegePreviewScaffold` 支持相同回调；
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

使用 Application 级别的状态；Provider ID 在升级后保持不变。

`startupModes` 指定 Tab 顺序，不接受重复项。有外部 Provider 时使用 `EXTERNAL` 的指定位置，未指定则追加；无 Provider 时隐藏该 Tab。

## 固定的手动启动脚本 {#manual-startup-script}

`manualShellScriptPath = null` 时显示完整 native 命令，设置路径后使用外置脚本。其他 Shell 进程可能在执行前修改脚本，启用前需考虑这一风险。

Android user 0 的路径相对于 `Android/data/<包名>`，需包含文件名。`priv.sh` 位于 `files` 同级：

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    manualShellScriptPath = "priv.sh",
)
```

UI 在 `Context.getExternalFilesDir(null)?.parentFile` 下写入，展示时使用 `/sdcard` 别名：

```shell
adb shell sh /sdcard/Android/data/<包名>/priv.sh
```

非 0 用户（含工作资料）使用完整 native 命令，因为 Shell 可能无法读取其外部存储。

UI 立即显示脚本命令，每个 ViewModel 在后台准备一次。内容一致则复用，变化时原子写入。应用升级后重新打开 UI 或调用 `prepareExternalShellScript(...)` 更新安装路径。目标内容变化会被覆盖，改配置路径不会删除旧文件。

存储不可用或读写失败时回退到 native 命令。使用应用外部目录内的相对路径；路径段应非空，排除 `.`、`..`、反斜杠和控制字符。Shell 必须能读取脚本。为兼容桌面 shell，使用字母、数字、`/`、`.`、`_`、`-`；其他字符使用 POSIX 引号规则。

不打开 UI 时，可直接准备脚本：

```kotlin
val scriptFile: File? = PrivilegeUi.prepareExternalShellScript("priv.sh")
val scriptPath: String? = scriptFile?.absolutePath
```

方法返回实际绝对路径 `File`。路径为空、非 0 用户、存储不可用或文件访问失败时返回 `null`，starter 解析错误直接抛出。方法会同步检查 APK 和写入文件，应放在 `Dispatchers.IO`。脚本准备与服务启动分别调用。

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

已连接时再次启动会请求重启确认。取消保留服务端，确认后先停止再替换；无权结束旧进程时通过 Snackbar 报错。自定义界面监听 `serverRestartConfirmation`，调用 `confirmServerRestart()` 或 `cancelServerRestart()` 继续挂起的操作。

## 观察服务端状态 {#server-state}

`PrivilegeScaffold` 已经在内部观察运行时并展示状态。应用的其他功能依赖连接时，
通过进程级的 `Privilege.serverState` 观察连接状态。

无需显示 UI 也能观察该 Flow，见[连接状态](./activation#connection-state)。

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

Scaffold 处理通知配对；通知输入不可用时，可将前台配对弹窗与系统设置分屏使用。

通过 `notificationPairingChannelId`（默认 `priv_ui_adb_pairing`）和 `notificationPairingNotificationId`（默认 `201`）避免与宿主冲突。ID 范围为 `1..Int.MAX_VALUE - 1`，连续占用两个值。专用渠道按需创建，不自动删除。

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

## 静默启动 {#exact-replay}

前台启动成功并建立 Binder 连接后，UI 会保存一个启动方式 ID：

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

静默启动使用已保存的方式。已保存的方式、授权或其他启动
条件不可用时返回 `null`，权限请求、配对和外部授权仍需在前台界面中完成。

前台与静默启动使用进程内互斥锁，多进程应用选定一个启动进程。静默启动期间禁用冲突操作；Root 授权失效时，Root 管理器仍可能显示提示。

UI 发起的前台启动成功并收到匹配的初始连接后，才会开启自动恢复。UI 前台启动
之外的初始连接（包括执行复制的手动 Shell 命令）保持当前设置。用户确认停止或选择
“关闭自动恢复”时关闭；断连、服务端死亡和静默启动失败保留原设置。
`PrivilegeUi.desiredEnabled` 会独立于当前服务端连接状态发布该设置。
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

`PrivilegePreviewScaffold()` 在宿主 Material 3 主题下预览 Android、JVM 和 WasmJS 页面。Android 实际功能使用 `PrivilegeScaffold`。

启动、配对和弹窗模拟成功，配对接受任意六位数字。复制使用宿主剪贴板；关闭宿主结束会话。可在[展示页](/zh/playground/)体验。

手动命令使用 `priv.kit.sample` 的随机安装路径。`useLegacyPackaging = true`（默认）显示解压库，`false` 显示 APK 内库的 `linker64` 命令；切换保留路径和会话。

## 宿主控制的页面 {#host-controlled-page}

`PrivilegeScreen` 在 Android、JVM 和 WasmJS 上显示宿主提供的 `PrivilegeUiScreenState`，由宿主实现 `PrivilegeUiActions` 和 `showFeedback`。

```kotlin
PrivilegeScreen(
    state = screenState,
    actions = hostActions,
    showFeedback = ::showMessage,
    systemPrompt = prompt, // PrivilegeUiPromptState(title, message)，或 null
    topBar = { AppTopBar() },
)
```

宿主实现全部回调、状态更新和取消，对不支持的操作给出反馈，复制使用宿主剪贴板。`requestBatteryOptimization` 返回是否打开请求或设置页。`interactionEnabled` 控制显示可用性，`canInteract` 在点击时检查交互许可，忙碌时应允许取消；`busy` 阻止冲突启动。

`startupModes` 不能为空；选中模式不在列表中时，展示第一个模式。宿主提供的消息和
`PrivilegeUiPromptState` 文本应已完成本地化。替换连接时递增 `connectionSerial`，
以重置连接相关的展示状态。列表作为快照使用，应发布新状态，不要原地修改列表。

宿主处理平台权限请求并提供提示文本。

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
传入的回调替代默认浏览器导航。教程入口在操作忙碌时仍可用。

教程由 `guide/startup-tutorial/StartupGuide.vue` 渲染，双语内容位于 `content.ts`。自行部署时需包含相关组件和样式，并保留 `adb`、`pairing`、`manual`、`tcp-ip`、`permission-restrictions` 等锚点。