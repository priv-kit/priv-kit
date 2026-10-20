---
description: Add Priv Kit to an Android app.
---

# Getting started {#getting-started}

[![Maven Central](https://img.shields.io/maven-central/v/io.github.priv-kit/priv-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.priv-kit/priv-core)

Requires Android API 26 or later.

## Add the dependencies {#add-dependencies}

`priv-ui` includes the Compose authorization page and exposes `priv-core`; no separate Core dependency is needed.

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui:<version>")
}
```

Choose `priv-core` directly for an application-owned authorization interface:

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## Configure native library packaging {#native-library-packaging}

Apps that support Android versions below 10 must set `useLegacyPackaging` to
`true`:

```kotlin
android {
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}
```

Android 10 and later have no such restriction. Priv Kit uses the startup command
that matches the value of `useLegacyPackaging`.

For example, on an arm64 device:

`useLegacyPackaging = true`:

```shell
adb shell /data/app/.../lib/arm64/libprivkitstarter.so
```

`useLegacyPackaging = false`:

```shell
adb shell /system/bin/linker64 '/data/app/.../base.apk!/lib/arm64-v8a/libprivkitstarter.so'
```

## Configure hidden API access {#hidden-api-access}

Configure [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
in the host app:

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

## Embed Privilege UI {#embed-privilege-ui}

Place `PrivilegeScaffold` in the app's Compose content:

```kotlin
PrivilegeScaffold()
```

The default page provides Root, ADB, and Manual startup. See [Privilege UI](./priv-ui) for pairing, configuration, and silent startup.

## Build a custom interface with priv-core {#custom-interface}

With `priv-core`, your app handles permissions, pairing, confirmations, polling, and errors.

The shortest Root and Wireless Debugging calls are:

```kotlin
val rootServer = Privilege.startRoot()
val adbServer = Privilege.startAdb()
```

Both are suspend APIs. Cancelling the owning coroutine closes the active
process, socket, or discovery session. See [startup methods](./activation) for
the complete `priv-core` flows.

## Choose a usage mode {#usage-mode}

- Use [Binder](./binder) for explicit raw Binder access.
- Use the [file proxy](./file-proxy) for basic absolute-path file access.
- Use [commands](./commands) for a bounded non-interactive process with streaming
  or captured output.
- Use [UserService](./user-service) for an app-defined AIDL service.
- Use [Privilege UI](./priv-ui) for the supplied authorization page.
- Read [startup methods](./activation) before replacing that page with a custom
  `priv-core` integration.

::: warning Active development
Use published versions and documented APIs. `priv-evdev` is experimental; internal classes have no compatibility guarantee.
:::
