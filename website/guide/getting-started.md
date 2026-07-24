---
description: Add Priv Kit to an Android application and start the first app-owned privileged runtime.
---

# Getting started

Priv Kit targets Android API 26 and later. It gives one application the
primitives to start, connect to, and use its own Privileged Server. Applications
can build their privileged operations directly on Binder or their own
UserService contracts.

## Add the dependencies

Use `priv-core` for the runtime. Add `priv-ui` only when the app needs the
optional Compose authorization surface and silent replay support.

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-core:<version>")
    implementation("io.github.priv-kit:priv-ui:<version>") // Optional
}
```

The compatibility guarantee covers the compile-time APIs visible after
referencing `priv-core` or `priv-ui`.

## Configure hidden API access

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

This runs during application initialization, before framework interfaces that
rely on hidden API access are called.

## Start a server

The shortest Root path is a suspend call:

```kotlin
val serverInfo = Privilege.startRoot()
```

ADB startup uses the same runtime and Binder handoff:

```kotlin
val serverInfo = Privilege.startAdb()
```

Both calls keep blocking transport work off the caller thread. Cancelling the
owning coroutine closes the active process, socket, or discovery session.

## Choose a usage mode

- Use [Binder](./binder) for explicit raw Binder access.
- Use [UserService](./user-service) for an app-defined AIDL service.
- Read [activation paths](./activation) before choosing Root, ADB, shell, or an
  external bridge.
- Add [Privilege UI](./priv-ui) when the host needs a reusable authorization
  page.

::: warning Active development
Use the version published for the release you target. The repository currently
defines `priv-core` and `priv-ui` as the supported integration surface; other
bytecode visibility is an implementation detail.
:::
