---
description: 将 Priv Kit 接入 Android 应用。
---

# 快速接入 {#getting-started}

[![Maven Central](https://img.shields.io/maven-central/v/io.github.priv-kit/priv-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.priv-kit/priv-core)

支持 Android API 26 及以上版本。

## 添加依赖 {#add-dependencies}

`priv-ui-m3` 提供 Material 3 授权页面，传递依赖 `priv-ui` 和 `priv-core`，无需重复声明 Core。自定义渲染时只需依赖 `priv-ui`。

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui-m3:<version>")
}
```

自定义授权界面可以直接依赖 `priv-core`：

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## 配置 native 库打包 {#native-library-packaging}

应用支持 Android 8 或 9 时，设置 `useLegacyPackaging = true`。

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

Android 10 及以上的启动命令支持两种打包方式。

## 配置 hidden API 访问 {#hidden-api-access}

在应用模块添加 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)。下例使用 Sample 的版本。

```kotlin
dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}
```

在应用的 `Application` 中初始化。

```kotlin
import android.app.Application
import android.content.Context
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L")
        }
    }
}
```

如果在应用基础包中新建了 `App`，在现有的 `<application>` 元素上加 `android:name=".App"`。已有 `Application` 类时，把初始化代码加到现有类即可。

## 嵌入 Privilege UI {#embed-privilege-ui}

在应用的 Compose Material 3 主题内放入启动页，即可使用 Root、ADB 和手动启动。配置见 [Privilege UI](./priv-ui)。

```kotlin
priv.kit.ui.m3.PrivilegeM3Page()
```

## 使用 priv-core 自定义界面 {#custom-interface}

如果应用自行处理授权和错误，按用户选择的启动方式调用对应的挂起 API。使用无线调试时，先完成配对再调用 `startAdb()`。见[启动方式](./activation)。

```kotlin
val serverInfo = Privilege.startRoot()
// 使用无线调试时改为：val serverInfo = Privilege.startAdb()
```

## 选择使用方式 {#usage-mode}

连接后即可使用 [Binder](./binder)、[文件](./file-proxy)、[命令](./commands) 或 [UserService](./user-service)。`priv-evdev` 处于实验阶段。
