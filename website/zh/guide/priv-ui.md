---
description: 使用推荐的 Compose 授权界面，并配置静默启动。
---

# Privilege UI {#privilege-ui}

`priv-ui` 是接入 Priv Kit 时推荐的起点。它通过 Compose 界面展示运行时授权状态、
提供启动入口，并在静默启动时复用最近一次成功的前台启动方式。只有应用需要
直接使用 `priv-core` 构建自定义界面时，才不必依赖该模块。

## 常用 API {#public-entry-points}

- `PrivilegeScaffold` 提供可嵌入 Compose 页面。
- `PrivilegeUiViewModel` 是可继承的 `AndroidViewModel` 控制器。
- `PrivilegeUiConfig` 用于启用启动方式和外部 Provider。
- `PrivilegeUiExternalStartProvider` 用于添加应用提供的外部启动方式。
- `PrivilegeUi.desiredEnabled` 以只读进程级 `StateFlow<Boolean>` 公开持久化的自动恢复
  意图。
- `PrivilegeUi.startSilently(...)` 在自动恢复开启时按上次成功的方式静默启动。只有
  应用明确需要忽略该设置时，才传入 `ignoreAutomaticRecoverySetting = true`。

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

顶层属性及其外部 Provider 不应持有 `Activity`。Provider ID 用于保存启动方式，
应用升级后也应保持不变。

`startupModes` 是控制授权 Tab 顺序的有序列表，传入重复项会直接报错。存在外部
Provider 时，`EXTERNAL` 会保留列表中的位置；如果列表中没有它，则自动追加到
末尾。没有外部 Provider 时，即使列表包含 `EXTERNAL` 也不会显示 External Tab。

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

这个只读状态在断连和静默启动失败后仍会保留。它不表示服务端当前是否已连接，
观察者也不能通过它修改设置。

## ADB 界面流程 {#adb-ui}

`priv-ui` 不实现 ADB 协议，也不自行启动服务端。它调用 `priv-core` 提供的配对、
发现、授权、TCP/IP 和启动 API，再通过 `PrivilegeScaffold` 展示状态并处理用户
操作。

通过 `PrivilegeUiConfig` 配置由 UI 管理的 ADB 流程：

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

### 无线调试界面 {#wireless-debugging-ui}

只有选中 ADB 启动方式时，ADB 面板才会定期检查无线调试和配对状态。它的前台流程
可以：

- 展示配对对话框，并通过 `priv-core` 提交六位配对码；
- 通过可选的通知配对流程接收配对码；
- 在系统要求时请求 `ACCESS_LOCAL_NETWORK`；
- 已保存的 ADB 密钥完成配对后，通过无线调试启动。

通知配对服务是 `priv-ui` 的内部组件。应用只需嵌入 `PrivilegeScaffold`，不要直接
启动 `PrivilegeAdbPairingService`。无法使用通知输入时，Scaffold 会保留前台配对
对话框，供用户与 Android 设置页配合分屏操作。权限警告会把“无通知继续”、设置中
授权后继续或取消返回给同一个挂起的配对协程。

`priv-core` 可以在具备权限时临时打开和关闭无线调试。`priv-ui` 只读取状态并把
对应配置传给 `priv-core`，不会自行写入 `Settings.Global`。

### TCP/IP 界面 {#tcp-ip-ui}

`PrivilegeUiConfig.tcpPort` 指定静态端口。
`PrivilegeUiConfig.adbTcpPolicy` 用于关闭 TCP/IP、优先使用现有静态端口，或在
无线调试完成配对后提供创建静态端口的入口。

通过界面调用 `priv-core` 执行 `adb tcpip` 时，内置 Scaffold 会先展示一次确认。
取消后 ADB 保持不变。自定义界面调用 `PrivilegeUiViewModel.enableTcpMode()` 或
`startStaticTcpAdb()` 时，必须监听 `staticTcpSwitchConfirmation`，展示自己的
警告，再调用 `confirmStaticTcpSwitch()` 或 `cancelStaticTcpSwitch()`。

UI 可以请求本机 ADB 密钥授权，并在系统返回结果后继续之前的启动操作。仅检查状态
不会修改 ADB 设置。

静默启动不会展示上述交互。它不会发起配对、请求权限、请求 TCP 授权或创建静态
端口；已保存的 ADB 启动方式尚未就绪时直接返回 `null`。

## 静默启动 {#exact-replay}

前台启动成功并建立 Binder 连接后，UI 会保存一个启动方式 ID：

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

静默启动只会尝试已保存的方式，不会切换到其他方式。已保存的方式、授权或其他启动
条件不可用时返回 `null`，权限请求、配对和外部授权仍需在前台界面中完成。

前台启动和静默启动在同一进程内不会同时执行。多进程应用必须只在一个指定进程中
初始化并调用 Priv Kit 的启动 API。静默启动期间，内置界面会暂时禁用其他启动
入口，并在完成后刷新运行时状态。如果 Root 管理器保存的授权已经失效，它仍可能
展示自己的授权界面。

UI 发起的前台启动成功并收到匹配的初始连接后，才会开启自动恢复。UI 前台启动
之外的初始连接（包括执行复制的手动 Shell 命令）不会开启自动恢复。只有用户确认
停止，或在内置提示中选择“关闭自动恢复”时才会关闭；断连、服务端死亡和静默启动
失败不会改变它。
`PrivilegeUi.desiredEnabled` 会独立于当前服务端连接状态发布这项持久化意图。
`startSilently(...)` 默认遵循自动恢复设置。只有应用明确需要忽略该设置时，才传入
`ignoreAutomaticRecoverySetting = true`。

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
