---
description: 将 Priv Kit 接入 Android 应用，并启动第一个应用自有的特权运行时。
---

# 快速接入 {#getting-started}

Priv Kit 支持 Android API 26 及以上版本。它为单个应用提供启动、连接并使用
自有 Privileged Server 的基础能力。应用可以通过 Binder 或自己的 UserService
实现特权功能。

## 添加依赖 {#add-dependencies}

优先使用 `priv-ui`。它提供 Compose 授权页面，并通过传递依赖公开 `priv-core`，
应用只声明这一个模块即可使用运行时、Binder 和 UserService API。

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui:<version>")
}
```

只有应用需要自定义授权界面时，才直接依赖 `priv-core`：

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## 配置 hidden API 访问 {#hidden-api-access}

应用必须配置
[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)：

```kotlin
class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }
}
```

## 嵌入 Privilege UI {#embed-privilege-ui}

在应用的 Compose 内容中放置 `PrivilegeScaffold`：

```kotlin
PrivilegeScaffold()
```

默认配置会展示 Root、ADB 和手动启动。ADB 界面会定期检查状态，并处理无线调试配对、
前台权限请求、TCP/IP 确认和启动。配置方式和静默启动见
[Privilege UI 指南](./priv-ui)。

## 使用 priv-core 自定义界面 {#custom-interface}

只有应用需要替换自带授权界面时，才直接使用 `priv-core`。此时应用需要自行处理
权限请求、配对码输入、确认界面、定期检查状态和错误展示。

最短的 Root 与无线调试调用如下：

```kotlin
val rootServer = Privilege.startRoot()
val adbServer = Privilege.startAdb()
```

两者都是 `suspend` 函数。取消调用它们的协程时，运行时会关闭当前进程、Socket
或查找端口的任务。
完整的 `priv-core` 流程见[启动方式](./activation)。

## 选择使用方式 {#usage-mode}

- 使用 [Binder](./binder) 访问系统服务或执行底层 Binder 调用。
- 使用 [UserService](./user-service) 运行应用自定义 AIDL 服务。
- 使用 [Privilege UI](./priv-ui) 接入自带授权页面。
- 需要用 `priv-core` 替换该页面时阅读[启动方式](./activation)。

::: warning 仍在活跃开发
请使用正式发布的版本。`priv-core` 和 `priv-ui` 是受支持的公开 API，
其他类和方法即使可以从字节码中访问，也属于实现细节。
:::
