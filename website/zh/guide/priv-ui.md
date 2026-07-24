---
description: 嵌入可选 Compose 授权界面，并配置精确静默重放。
---

# Privilege UI

`priv-ui` 是可选 Compose 模块，用于展示运行时授权状态、提供激活入口，并
精确重放最近一次成功的前台启动方式。

## 公开入口

- `PrivilegeScaffold` 提供可嵌入 Compose 页面。
- `PrivilegeUiViewModel` 是可继承的 `AndroidViewModel` 控制器。
- `PrivilegeUiConfig` 用于启用启动方式和外部 Provider。
- `PrivilegeUiExternalStartProvider` 用于接入应用自有外部路径。
- `PrivilegeUi.startSilentlyIfEnabled(...)` 只在期望状态开启时重放。
- `PrivilegeUi.startSilently(...)` 无论期望状态如何，都会执行相同的精确重放。

Scaffold 使用宿主应用的 Material 3 主题。需要品牌化浅色、深色或动态配色时，
应在应用自己的主题中包裹它。

## 保持一份 Application 作用域配置

只构造一次外部 Provider 和 `PrivilegeUiConfig`，然后把同一个实例传给前台
ViewModel 与无界面入口：

```kotlin
class App : Application() {
    val privilegeUiConfig by lazy {
        PrivilegeUiConfig(
            externalStartProviders = listOf(myShizukuProvider),
        )
    }
}

val serverInfo = PrivilegeUi.startSilentlyIfEnabled(
    context = app,
    config = app.privilegeUiConfig,
)
```

让外部 Provider 保持 Application 作用域并独立于 `Activity`。Provider ID
是持久化键，应在应用升级后继续保持稳定。

## 嵌入 Scaffold

```kotlin
class MyPrivilegeUiViewModel(
    application: Application,
) : PrivilegeUiViewModel(
    application,
    (application as App).privilegeUiConfig,
) {
    override fun onBackClick(): Boolean {
        return true
    }

    override fun onConnected(serverInfo: PrivilegeServerInfo) {
        // 新连接建立后更新宿主状态。
    }
}

PrivilegeScaffold(
    viewModel = viewModel<MyPrivilegeUiViewModel>(),
)
```

Scaffold 拥有自己的 Activity Result launcher，并把权限结果返回给同一个挂起
的 ViewModel 操作。

## 理解精确重放

当前台操作收到匹配的初始 Binder 连接后，UI 会保存一个 method ID：

- `root`
- `adb-wireless`
- `adb-tcpip`
- `external:<providerId>`

静默重放是无界面、严格匹配启动方式的操作。它使用已保存的方式；历史、授权或
启动条件不可用时返回 `null`，并把权限请求、配对和外部授权留给前台流程。

前台与静默尝试共享同一个进程内启动门。多进程应用必须指定唯一进程初始化并
调用 Priv Kit 启动入口。已接受的前台副作用会持有交互租约直至完成。静默尝试
持有启动门期间，内置 UI 会禁用带副作用的入口，并在重新启用前对账运行时
状态。如果 Root 管理器保存的授权已经失效，它仍可能展示自己的授权界面。

## 期望状态

期望状态以单字节 `1` 或 `0` 保存到
`filesDir/.priv-kit/ui-desired-enabled`。每个已接受的 `INITIAL_LAUNCH`
连接都会写入 `1`，包括通过复制外部 shell 命令启动的服务端。
`OWNER_RECONNECT`、断连、进程死亡和重放失败会保留已有值。用户确认停止，或在
内置断连提示中选择“关闭自动恢复”时，会写入 `0`。

该状态单独记录用户意图，与当前服务端是否存活相互独立。匹配 UI 操作之外的
启动可以开启期望状态，同时保持重放历史不变。存在 UI 确认过的启动方式时，
静默恢复会按该方式启动；否则 `startSilentlyIfEnabled(...)` 返回 `null`，内置
警告仍然显示。

最近一次由 UI 确认的启动方式保存在
`filesDir/.priv-kit/ui-start-method`。`startSilentlyIfEnabled(...)` 会先检查
期望状态字节，再执行与 `startSilently(...)` 相同的精确重放。

## 宿主应用负责的集成

宿主应用可以按需接入 Shizuku 绑定，以及包管理、输入、设置、app-ops 或日志
工具。Privilege UI 专注于运行时状态、激活和恢复。
