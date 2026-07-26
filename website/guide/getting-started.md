---
description: Add Priv Kit to an Android application and start the first app-owned privileged runtime.
---

# Getting started {#getting-started}

[![Maven Central](https://img.shields.io/maven-central/v/io.github.priv-kit/priv-core.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.priv-kit/priv-core)

Priv Kit targets Android API 26 and later. It gives one application the
primitives to start, connect to, and use its own Privileged Server. Applications
can build their privileged operations directly on Binder or their own
UserService contracts.

## Add the dependencies {#add-dependencies}

Start with `priv-ui`. It provides the Compose authorization page and exposes
`priv-core` transitively, so the application can use the runtime, Binder, and
UserService APIs without declaring both modules.

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-ui:<version>")
}
```

Depend on `priv-core` directly only when the application is building its own
authorization interface:

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
}
```

## Configure hidden API access {#hidden-api-access}

The host app must configure
[HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass):

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

The default configuration presents Root, ADB, and Manual startup. The ADB
surface handles status polling, Wireless Debugging pairing, foreground
permission requests, TCP/IP confirmation, and startup. Read the
[Privilege UI guide](./priv-ui) for configuration and silent replay.

## Build a custom interface with priv-core {#custom-interface}

Use `priv-core` directly when the application needs to replace the supplied
authorization interface. The host then owns permission prompts, pairing input,
confirmation surfaces, status polling, and error presentation.

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
- Use [UserService](./user-service) for an app-defined AIDL service.
- Use [Privilege UI](./priv-ui) for the supplied authorization page.
- Read [startup methods](./activation) before replacing that page with a custom
  `priv-core` integration.

::: warning Active development
Use the version published for the release you target. The repository currently
defines `priv-core` and `priv-ui` as the supported integration surface; other
bytecode visibility is an implementation detail.
:::
