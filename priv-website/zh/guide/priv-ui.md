---
description: 接入 Compose 授权页面并配置自动恢复。
---

# Privilege UI {#privilege-ui}

用 `priv-ui` 接入 Compose 启动页，处理启动、授权和自动恢复。

## 常用 API {#public-entry-points}

- `PrivilegeScaffold` 展示 Android 启动页。
- `PrivilegeUiViewModel` 和 `PrivilegeUiConfig` 配置启动方式和外部 Provider。
- `PrivilegeUi.startSilently()` 用上次成功的方式恢复服务。

## 权限解决方案入口 {#permission-solutions}

「查看解决方案」打开[权限指南](./startup-tutorial#permission-restrictions)，「查看受限权限」列出被拒绝的权限。传入 `onViewPermissionSolutions` 可以自定义跳转。

```kotlin
PrivilegeScaffold(
    viewModel = viewModel,
    onViewPermissionSolutions = {
        navController.navigate("permission-help")
    },
)
```

## 复用进程级配置 {#application-scoped-config}

前台 ViewModel 和静默启动共用同一份配置。`startupModes` 决定 Tab 顺序。注册外部 Provider 后，如果未指定外部 Tab 的位置，UI 会把它放在最后。

```kotlin
val privilegeUiConfig = PrivilegeUiConfig()

// 从指定应用进程的协程调用。
PrivilegeUi.startSilently(config = privilegeUiConfig)
```

## 固定的手动启动脚本 {#manual-startup-script}

给 Android 用户 0 配置相对路径后，页面会提供更短的启动命令。脚本位于应用外部目录，其他 Shell 进程可能可以修改它。

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    manualShellScriptPath = "priv.sh",
)
```

```shell
adb shell sh /sdcard/Android/data/<包名>/priv.sh
```

其他用户或存储不可用时，页面展示 native 命令。若要在页面之外生成或刷新脚本，可调用 `PrivilegeUi.prepareExternalShellScript("priv.sh")`。存储可用时，该调用会同步读写文件。

## 嵌入 Scaffold {#embed-scaffold}

让 ViewModel 使用进程级配置，再把它传给 Scaffold。

```kotlin
class MyPrivilegeUiViewModel(application: Application) :
    PrivilegeUiViewModel(application, privilegeUiConfig)

PrivilegeScaffold(viewModel = viewModel<MyPrivilegeUiViewModel>())
```

Scaffold 处理权限结果，并在重启已连接的服务端前询问用户。

## 观察服务端状态 {#server-state}

依赖在线服务的功能可以监听 [`Privilege.serverState`](./activation#connection-state)。用 `PrivilegeUi.desiredEnabled` 展示已保存的自动恢复设置。

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

## ADB 界面流程 {#adb-ui}

通过 `PrivilegeUiConfig` 设置无线调试和静态 TCP/IP。

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

### 无线调试界面 {#wireless-debugging-ui}

可在前台对话框或通知里输入配对码。通知输入不可用时，可与系统设置分屏。系统要求本地网络权限时，页面会发起申请。

通知默认值与应用冲突时，设置 `notificationPairingChannelId` 和 `notificationPairingNotificationId`。配对使用两个连续的通知 ID。

#### 配对通知语言 {#pairing-notification-language}

应用切换语言时，在主线程调用 `PrivilegeUi.setLocale(locale)`。传入 `null` 可跟随 Android 应用语言。

### TCP/IP 界面 {#tcp-ip-ui}

打开或切换 ADB TCP/IP 端口前，Scaffold 会询问用户。自建页面可监听 `staticTcpSwitchConfirmation`，并调用 `confirmStaticTcpSwitch()` 或 `cancelStaticTcpSwitch()`。

## 静默启动 {#exact-replay}

前台界面启动成功后会保存启动方式并开启自动恢复。用 `PrivilegeUi.getLastStartMethod()` 读取记录。在 UI 确认停止服务或关闭自动恢复时，会清除恢复设置。

`PrivilegeUi.startSilently()` 使用已保存的方式和现有授权。配对和授权应在前台完成。关闭自动恢复后仍要尝试启动时，传入 `ignoreAutomaticRecoverySetting = true`。多进程应用在指定进程调用。

## 添加 Shizuku {#shizuku}

在应用模块的 `build.gradle.kts` 中添加依赖，版本与 `priv-ui` 一致。
页面示例还用到 `lifecycle-viewmodel-compose`，版本沿用应用的 Lifecycle 版本。
在 `Application.attachBaseContext()` 中配置 [hidden API 访问](./getting-started#hidden-api-access)。

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-external-shizuku:$privKitVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
}
```

在 `PrivilegeScaffold` 使用的配置中注册 `PrivilegeExternalShizuku`。

```kotlin
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.external.shizuku.PrivilegeExternalShizuku
import priv.kit.ui.PrivilegeScaffold
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiViewModel

private val privilegeUiConfig = PrivilegeUiConfig(
    externalStartProviders = listOf(PrivilegeExternalShizuku),
)

class StartupViewModel(application: Application) :
    PrivilegeUiViewModel(application, privilegeUiConfig)

@Composable
fun StartupScreen() {
    PrivilegeScaffold(viewModel = viewModel<StartupViewModel>())
}
```

外部 Tab 会显示 Shizuku 卡片。尝试启动后，版本不支持或授权被拒绝等详情会显示在启动日志里。
需要自动恢复时，把同一份 `privilegeUiConfig` 传给
`PrivilegeUi.startSilently()`。见 [Shizuku 的版本要求和迁移步骤](./activation#shizuku-v13)。

自定义外部启动方式时，实现 Core 的 `PrivilegeExternalStartProvider`。在 `getLabel(context)` 中用
`context.getText(R.string.provider_name)` 等方式返回本地化标题。结果中的 `authorized` 为
`null`、`false` 或 `true`，分别表示不可用、未授权或已授权，可附上本地化 `message`。
收到日志监听器时，将启动输出传给它。应用升级时保持 Provider ID 不变，以便静默启动沿用该入口。
见 [Sample 配置](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt)。

## 跨平台展示 {#multiplatform-preview}

用 `PrivilegePreviewScaffold()` 在 Android、JVM 和 WasmJS 上体验模拟启动和配对。任意六位配对码均可。可在[展示页](/zh/playground/)体验。

## 宿主控制的页面 {#host-controlled-page}

宿主自行管理状态和操作时可用 `PrivilegeScreen`。提供非空的 `startupModes`、操作回调和本地化反馈。

```kotlin
PrivilegeScreen(
    state = screenState,
    actions = hostActions,
    showFeedback = ::showMessage,
    systemPrompt = prompt, // PrivilegeUiPromptState(title, message)，或 null
    topBar = { AppTopBar() },
)
```

## 用户教程链接 {#startup-tutorial-links}

内置链接会打开当前语言的[启动教程](./startup-tutorial)。设置 `startupTutorialUrl` 可以替换教程，或用 `onViewStartupTutorial` 自定义跳转。权限帮助优先使用 `onViewPermissionSolutions`。
