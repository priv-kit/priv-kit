---
description: Add Priv Kit to an Android app.
---

# Getting started {#getting-started}

[![Maven Central](https://img.shields.io/maven-central/v/io.github.priv-kit/priv-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.priv-kit/priv-core)

Requires Android API 26 or later.

## Add the dependencies {#add-dependencies}

`priv-ui-m3` provides the Material 3 authorization page and exposes `priv-ui` and `priv-core`; no separate Core dependency is needed. For your own renderer, depend on `priv-ui` instead.

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui-m3:<version>")
}
```

Choose `priv-core` directly for an application-owned authorization interface:

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## Configure native library packaging {#native-library-packaging}

Set `useLegacyPackaging = true` if the app supports Android 8 or 9.

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

On Android 10 and later, the generated startup command works with either packaging mode.

## Configure hidden API access {#hidden-api-access}

Add [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) to the app module. This is the version used by Sample.

```kotlin
dependencies {
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.2")
}
```

Initialize it in your `Application`.

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

For a new `App` in your app’s base package, set `android:name=".App"` on the existing `<application>` element. If the app already has an `Application` class, add the initialization there.

## Embed Privilege UI {#embed-privilege-ui}

Place the page inside your app’s Compose Material 3 theme for Root, ADB, and manual startup. See [Privilege UI](./priv-ui) to configure it.

```kotlin
priv.kit.ui.m3.PrivilegeM3Page()
```

## Build a custom interface with priv-core {#custom-interface}

If your app handles authorization and errors, choose one suspend API for the user’s startup method. Pair Wireless Debugging before calling `startAdb()`. See [startup methods](./activation).

```kotlin
val serverInfo = Privilege.startRoot()
// For Wireless Debugging instead: val serverInfo = Privilege.startAdb()
```

## Choose a usage mode {#usage-mode}

Use the connected server for [Binder](./binder), [files](./file-proxy), [commands](./commands), or [UserService](./user-service). `priv-evdev` is experimental.
