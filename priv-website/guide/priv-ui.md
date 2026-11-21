---
description: Add the Compose authorization page and configure automatic recovery.
---

# Privilege UI {#privilege-ui}

Use `priv-ui-m3` for the default Material 3 startup page, or `priv-ui` for theme-independent state, actions and custom rendering.

## Default page {#default-page}

Add the dependency at the same version as the other modules in your project:

```kotlin
implementation("io.github.priv-kit:priv-ui-m3:$privKitVersion")
```

Call the page inside your app's Material 3 theme:

```kotlin
import priv.kit.ui.m3.PrivilegeM3Page

MaterialTheme {
    PrivilegeM3Page()
}
```

The page supplies its ViewModel, permission requests, pairing and confirmations by default. No additional setup is needed. Pass `onBack` to connect app navigation:

```kotlin
PrivilegeM3Page(onBack = { navController.popBackStack() })
```

## Custom configuration {#application-scoped-config}

Extend `PrivilegeM3ViewModel` with your configuration and pass it to the page:

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    startupModes = listOf(PrivilegeUiStartupMode.ROOT, PrivilegeUiStartupMode.ADB),
)

class StartupViewModel(application: Application) :
    PrivilegeM3ViewModel(application, privilegeUiConfig)

PrivilegeM3Page(viewModel = viewModel<StartupViewModel>())
```

`startupModes` controls default methods, automatic startup order, observed sources and M3 tab order; explicit start actions remain available. Omitting `ADB` disables ADB polling. External providers append `EXTERNAL` unless you specify its position. The resulting list must contain at least one method.

## Fixed manual startup script {#manual-startup-script}

Set a relative path to generate a shorter command for Android user 0. The script lives in the app’s external directory. Other shell processes may be able to modify it.

```kotlin
val privilegeUiConfig = PrivilegeUiConfig(
    manualShellScriptPath = "priv.sh",
)
```

```shell
adb shell sh /sdcard/Android/data/<package>/priv.sh
```

Other users and storage failures use the native command. To prepare or refresh the script without opening the UI, call `PrivilegeUi.prepareExternalShellScript("priv.sh")`. The call performs synchronous file I/O when storage is available.

## Observe the server state {#server-state}

Observe [`Privilege.serverState`](./activation#connection-state) for features that need a live server. Observe `PrivilegeUi.desiredEnabled` to show the saved automatic recovery setting.

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

## ADB configuration {#adb-ui}

Configure Wireless Debugging and static TCP/IP through `PrivilegeUiConfig`:

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

Set `notificationPairingChannelId` and `notificationPairingNotificationId` if the defaults conflict with your app. Pairing reserves two consecutive notification IDs. Call `PrivilegeUi.setLocale(locale)` on the main thread when the app’s language changes; pass `null` to follow Android’s application language.

## Silent startup {#exact-replay}

Call from a coroutine in the designated app process, using the foreground configuration:

```kotlin
PrivilegeUi.startSilently(config = privilegeUiConfig)
```

A successful foreground UI start saves the method and enables automatic recovery. `PrivilegeUi.getLastStartMethod()` reads the saved method. Stopping the server through the UI or disabling automatic recovery clears the recovery setting.

`PrivilegeUi.startSilently()` uses the saved method and existing authorization. Complete pairing and permission requests in the foreground. Pass `ignoreAutomaticRecoverySetting = true` to attempt a silent start when recovery is disabled. Call it from one designated process.

## Add Shizuku {#shizuku}

Add the dependency at the same version as the other modules and configure [hidden API access](./getting-started#hidden-api-access) in `Application.attachBaseContext()`:

```kotlin
implementation("io.github.priv-kit:priv-external-shizuku:$privKitVersion")
```

Use this configuration with `StartupViewModel` above:

```kotlin
import priv.kit.external.shizuku.PrivilegeExternalShizuku

val privilegeUiConfig = PrivilegeUiConfig(
    externalStartProviders = listOf(PrivilegeExternalShizuku),
)
```

Startup failures appear in the page log. See the [Shizuku guide](./activation#shizuku-v13) for version requirements. For custom external startup, implement Core’s `PrivilegeExternalStartProvider`; see the [sample configuration](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt).

## Multiplatform preview {#multiplatform-preview}

Use `PrivilegeM3Preview()` to try simulated startup and pairing on Android, JVM, and WasmJS. Any six-digit pairing code works. Try the [playground](/playground/).

## Help links {#startup-tutorial-links}

Built-in links open the localized [startup tutorial](./startup-tutorial) and [permission guide](./startup-tutorial#permission-restrictions). Set `startupTutorialUrl` to replace the tutorial or use `onViewStartupTutorial` for custom navigation. Override permission help separately with `onViewPermissionSolutions`.

## Custom rendering {#custom-rendering}

Use `priv-ui` with your own components and theme. It does not depend on Material 3.

```kotlin
implementation("io.github.priv-kit:priv-ui:$privKitVersion")
```

```kotlin
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.ui.*

@OptIn(ExperimentalPrivilegeUiApi::class)
@Composable
fun CustomStartupPage() {
    val vm: PrivilegeUiViewModel = viewModel()
    val context = LocalContext.current

    PrivilegeUiHost(
        viewModel = vm,
        onMessage = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
    ) { ui ->
        AppStartupContent(ui)
    }
}
```

Implement `AppStartupContent` with your own UI: read `ui.state`, invoke `ui.actions`, and handle pairing input and confirmations. The Host supplies lifecycle and platform bindings.
