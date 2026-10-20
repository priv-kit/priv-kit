---
description: 将 Priv Kit 接入 Android 应用。
---

# 快速接入 {#getting-started}

[![Maven Central](https://img.shields.io/maven-central/v/io.github.priv-kit/priv-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.priv-kit/priv-core)

支持 Android API 26 及以上版本。

## 添加依赖 {#add-dependencies}

`priv-ui` 包含 Compose 授权页面并传递依赖 `priv-core`，无需重复声明 Core。

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui:<version>")
}
```

自定义授权界面可以直接依赖 `priv-core`：

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## 配置 native 库打包 {#native-library-packaging}

支持 Android 10 以下版本时，将 `useLegacyPackaging` 设置为 `true`：

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

Android 10 及以上版本没有此限制。Priv Kit 会根据 `useLegacyPackaging` 的值使用
对应的启动命令。

以 arm64 设备为例：

`useLegacyPackaging = true`：

```shell
adb shell /data/app/.../lib/arm64/libprivkitstarter.so
```

`useLegacyPackaging = false`：

```shell
adb shell /system/bin/linker64 '/data/app/.../base.apk!/lib/arm64-v8a/libprivkitstarter.so'
```

## 配置 hidden API 访问 {#hidden-api-access}

在应用中配置
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

默认页面提供 Root、ADB 和手动启动，配对、配置及静默启动见 [Privilege UI](./priv-ui)。

## 使用 priv-core 自定义界面 {#custom-interface}

使用 `priv-core` 时，应用自行处理权限、配对、确认、状态检查和错误。

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
- 使用[文件代理](./file-proxy)执行基础的绝对路径文件操作。
- 使用[命令执行](./commands)启动有界的非交互进程，并流式读取或汇总输出。
- 使用 [UserService](./user-service) 运行应用自定义 AIDL 服务。
- 使用 [Privilege UI](./priv-ui) 接入自带授权页面。
- 需要用 `priv-core` 替换该页面时阅读[启动方式](./activation)。

::: warning 仍在活跃开发
使用发布版本及文档中的公开 API。`priv-evdev` 为实验模块，内部类不承诺兼容性。
:::
