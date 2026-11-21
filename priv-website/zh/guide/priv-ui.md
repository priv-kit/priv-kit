---
description: 接入 Compose 授权页面并配置自动恢复。
---

# Privilege UI {#privilege-ui}

用 `priv-ui-m3` 接入默认 Material 3 启动页，或用 `priv-ui` 接入主题无关的状态、操作和自定义渲染。

## 默认页面 {#default-page}

添加依赖，版本与项目中其他模块一致：

```kotlin
implementation("io.github.priv-kit:priv-ui-m3:$privKitVersion")
```

在应用的 Material 3 主题内调用即可：

```kotlin
import priv.kit.ui.m3.PrivilegeM3Page

MaterialTheme {
    PrivilegeM3Page()
}
```

页面默认提供 ViewModel、权限申请、配对和确认交互，无需额外配置。需要接入应用导航时，可传入 `onBack`：

```kotlin
PrivilegeM3Page(onBack = { navController.popBackStack() })
```

## 自定义配置 {#application-scoped-config}

继承 `PrivilegeM3ViewModel`，传入配置后交给页面：

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    startupModes = listOf(PrivilegeUiStartupMode.ROOT, PrivilegeUiStartupMode.ADB),
)

class StartupViewModel(application: Application) :
    PrivilegeM3ViewModel(application, privilegeUiConfig)

PrivilegeM3Page(viewModel = viewModel<StartupViewModel>())
```

`startupModes` 控制默认方式、自动启动顺序、观察来源与 M3 Tab 顺序，不限制显式启动操作。不包含 `ADB` 时不进行 ADB 轮询。外部 Provider 自动补入 `EXTERNAL`，也可显式指定其位置；最终必须至少有一种方式。

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

## 观察服务端状态 {#server-state}

依赖在线服务的功能可以监听 [`Privilege.serverState`](./activation#connection-state)。用 `PrivilegeUi.desiredEnabled` 展示已保存的自动恢复设置。

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

## ADB 配置 {#adb-ui}

通过 `PrivilegeUiConfig` 配置无线调试和静态 TCP/IP：

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

通知默认值与应用冲突时，设置 `notificationPairingChannelId` 和 `notificationPairingNotificationId`。配对使用两个连续的通知 ID。应用切换语言时，在主线程调用 `PrivilegeUi.setLocale(locale)`；传入 `null` 跟随 Android 应用语言。

## 静默启动 {#exact-replay}

从指定应用进程的协程调用，复用前台配置：

```kotlin
PrivilegeUi.startSilently(config = privilegeUiConfig)
```

前台界面启动成功后会保存启动方式并开启自动恢复。用 `PrivilegeUi.getLastStartMethod()` 读取记录。在 UI 确认停止服务或关闭自动恢复时，会清除恢复设置。

`PrivilegeUi.startSilently()` 使用已保存的方式和现有授权。配对和授权应在前台完成。关闭自动恢复后仍要尝试启动时，传入 `ignoreAutomaticRecoverySetting = true`。多进程应用在指定进程调用。

## 添加 Shizuku {#shizuku}

添加与其他模块相同版本的依赖，并在 `Application.attachBaseContext()` 中配置 [hidden API 访问](./getting-started#hidden-api-access)：

```kotlin
implementation("io.github.priv-kit:priv-external-shizuku:$privKitVersion")
```

将以下配置传给上面的 `StartupViewModel`：

```kotlin
import priv.kit.external.shizuku.PrivilegeExternalShizuku

val privilegeUiConfig = PrivilegeUiConfig(
    externalStartProviders = listOf(PrivilegeExternalShizuku),
)
```

启动失败原因见页面日志。版本要求见 [Shizuku 指南](./activation#shizuku-v13)。自定义外部启动方式可实现 Core 的 `PrivilegeExternalStartProvider`，参考 [Sample 配置](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt)。

## 跨平台展示 {#multiplatform-preview}

用 `PrivilegeM3Preview()` 在 Android、JVM 和 WasmJS 上体验模拟启动和配对。任意六位配对码均可。可在[展示页](/zh/playground/)体验。

## 帮助链接 {#startup-tutorial-links}

内置链接打开当前语言的[启动教程](./startup-tutorial)和[权限指南](./startup-tutorial#permission-restrictions)。设置 `startupTutorialUrl` 可替换教程，或用 `onViewStartupTutorial` 自定义跳转；权限帮助由 `onViewPermissionSolutions` 单独覆盖。

## 自定义渲染 {#custom-rendering}

使用 `priv-ui` 接入自己的组件和主题，它不依赖 Material 3。

```kotlin
implementation("io.github.priv-kit:priv-ui:$privKitVersion")
```

```kotlin
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.*

@OptIn(ExperimentalPrivilegeUiApi::class)
@Composable
fun CustomStartupPage() {
    val vm: PrivilegeUiViewModel = viewModel()
    val context = LocalContext.current

    PrivilegeUiHost(
        viewModel = vm,
        onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    ) { ui ->
        AppStartupContent(ui)
    }
}
```

`AppStartupContent` 由应用实现：读取 `ui.state`，通过 `ui.actions` 执行操作，并绘制配对输入和确认交互。Host 自动处理生命周期和平台绑定。
