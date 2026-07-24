---
description: 将 Priv Kit 接入 Android 应用，并启动第一个应用自有的特权运行时。
---

# 快速接入

Priv Kit 支持 Android API 26 及以上版本。它为单个应用提供启动、连接并使用
自有 Privileged Server 的原语。应用可以直接基于 Binder 或自己的 UserService
契约构建特权操作。

## 添加依赖

使用 `priv-core` 接入运行时。只有在应用需要可选 Compose 授权界面和静默
重放能力时，才添加 `priv-ui`。

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // 可选
}
```

兼容性承诺覆盖接入 `priv-core` 或 `priv-ui` 后可见的编译期 API。

## 配置 hidden API 访问

宿主应用必须配置
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

这段代码会在应用初始化期间执行，早于依赖 hidden API 的 framework 接口调用。

## 启动服务端

最短的 Root 路径是一个挂起调用：

```kotlin
val serverInfo = Privilege.startRoot()
```

ADB 启动复用同一套运行时与 Binder 移交：

```kotlin
val serverInfo = Privilege.startAdb()
```

两种调用都会把阻塞传输工作放到调用线程之外。取消所属协程时，运行时会关闭
当前进程、Socket 或发现会话。

## 选择使用方式

- 使用 [Binder](./binder) 进行显式 raw Binder 访问。
- 使用 [UserService](./user-service) 承载应用自定义 AIDL 服务。
- 选择 Root、ADB、shell 或外部桥之前阅读[激活方式](./activation)。
- 宿主需要可复用授权页面时添加 [Privilege UI](./priv-ui)。

::: warning 仍在活跃开发
请使用目标 release 已发布的版本。`priv-core` 和 `priv-ui` 是受支持的接入面，
其他字节码可见内容属于实现细节。
:::
