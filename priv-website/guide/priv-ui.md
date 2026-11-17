---
description: Add the Compose authorization page and configure automatic recovery.
---

# Privilege UI {#privilege-ui}

Use `priv-ui` to add a Compose page for startup, authorization, and automatic recovery.

## Public entry points {#public-entry-points}

- `PrivilegeScaffold` shows the Android page.
- `PrivilegeUiViewModel` and `PrivilegeUiConfig` control startup methods and providers.
- `PrivilegeUi.startSilently()` restores the last successful method.

## Permission troubleshooting action {#permission-solutions}

**View solutions** opens the [permission guide](./startup-tutorial#permission-restrictions). **View restricted permissions** lists the denied permissions. Pass `onViewPermissionSolutions` to use your own navigation.

```kotlin
PrivilegeScaffold(
    viewModel = viewModel,
    onViewPermissionSolutions = {
        navController.navigate("permission-help")
    },
)
```

## Share one configuration {#application-scoped-config}

Share one config between the foreground ViewModel and silent startup. `startupModes` sets tab order. Registering a provider adds the External tab if you haven’t specified a position.

```kotlin
val privilegeUiConfig = PrivilegeUiConfig()

// From a coroutine in the designated app process.
PrivilegeUi.startSilently(config = privilegeUiConfig)
```

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

Other users and storage failures use the native command. To prepare or refresh the script without opening the UI, call `PrivilegeUi.prepareExternalShellScript("priv.sh")` on `Dispatchers.IO`.

## Embed the scaffold {#embed-scaffold}

Use your process-wide config in a ViewModel and pass it to the scaffold.

```kotlin
class MyPrivilegeUiViewModel(application: Application) :
    PrivilegeUiViewModel(application, privilegeUiConfig)

PrivilegeScaffold(viewModel = viewModel<MyPrivilegeUiViewModel>())
```

The scaffold handles permission results and asks for confirmation before restarting a connected server.

## Observe the server state {#server-state}

Observe [`Privilege.serverState`](./activation#connection-state) for features that need a live server. Observe `PrivilegeUi.desiredEnabled` to show the saved automatic recovery setting.

```kotlin
val desiredEnabled by PrivilegeUi.desiredEnabled.collectAsStateWithLifecycle()
```

## ADB settings {#adb-ui}

Configure Wireless Debugging and static TCP/IP through `PrivilegeUiConfig`.

```kotlin
val config = PrivilegeUiConfig(
    tcpPort = PRIVILEGE_ADB_DEFAULT_TCP_PORT,
    adbTcpPolicy = PrivilegeUiAdbTcpPolicy.PREFER_EXISTING,
    enableManagedWirelessAdb = true,
)
```

### Wireless Debugging UI {#wireless-debugging-ui}

Pair from the foreground dialog or the notification. Use split-screen with Android Settings if notification input is unavailable. The page requests local network access on Android versions that require it.

Set `notificationPairingChannelId` and `notificationPairingNotificationId` if the defaults conflict with your app. Pairing reserves two consecutive notification IDs.

#### Pairing notification language {#pairing-notification-language}

Call `PrivilegeUi.setLocale(locale)` on the main thread when the app’s language changes. Pass `null` to use Android’s application language.

### TCP/IP UI {#tcp-ip-ui}

The scaffold asks for confirmation before opening or changing an ADB TCP/IP port. If you use a custom page, observe `staticTcpSwitchConfirmation` and call `confirmStaticTcpSwitch()` or `cancelStaticTcpSwitch()`.

## Silent startup {#exact-replay}

A successful foreground UI start saves the method and enables automatic recovery. `PrivilegeUi.getLastStartMethod()` reads the saved method. Stopping the server through the UI or disabling automatic recovery clears the recovery setting.

`PrivilegeUi.startSilently()` uses the saved method and existing authorization. Complete pairing and permission requests in the foreground. Pass `ignoreAutomaticRecoverySetting = true` to attempt a silent start when recovery is disabled. Call it from one designated process.

## Add Shizuku {#shizuku}

In the app module’s `build.gradle.kts`, add the dependency at the same version as `priv-ui`.
The page example also uses `lifecycle-viewmodel-compose` at your app’s Lifecycle version.
Set up [hidden API access](./getting-started#hidden-api-access) in `Application.attachBaseContext()`.

```kotlin
dependencies {
    implementation("io.github.priv-kit:priv-external-shizuku:$privKitVersion")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:$lifecycleVersion")
}
```

Register `PrivilegeExternalShizuku` in the configuration used by `PrivilegeScaffold`.

```kotlin
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import priv.kit.external.shizuku.PrivilegeExternalShizuku
import priv.kit.ui.PrivilegeScaffold
import priv.kit.ui.PrivilegeUiConfig
import priv.kit.ui.PrivilegeUiViewModel

private val privilegeUiConfig = PrivilegeUiConfig(
    externalStartProviders = listOf(PrivilegeExternalShizuku),
)

class StartupViewModel(application: Application) :
    PrivilegeUiViewModel(application, privilegeUiConfig)

@Composable
fun StartupScreen() {
    PrivilegeScaffold(viewModel = viewModel<StartupViewModel>())
}
```

The External tab shows a Shizuku card. After a startup attempt, details such as an unsupported API
version or denied authorization appear in the startup log. Reuse `privilegeUiConfig` with
`PrivilegeUi.startSilently()` for automatic recovery. See the
[Shizuku requirements and migration steps](./activation#shizuku-v13).

For a custom external startup method, implement `PrivilegeExternalStartProvider` from Core.
Use `getLabel(context)` to supply a localized title, for example `context.getText(R.string.provider_name)`.
Return `authorized = null`, `false`, or `true` for unavailable, unauthorized, or authorized,
with an optional localized `message`. Pass starter output to the supplied log listener when
present. Keep the provider ID stable across upgrades for silent startup.
See the [sample configuration](https://github.com/priv-kit/priv-kit/blob/main/priv-sample/src/main/kotlin/priv/kit/sample/startup/PrivilegeSampleUiIntegration.kt).

## Multiplatform preview {#multiplatform-preview}

Use `PrivilegePreviewScaffold()` to try simulated startup and pairing on Android, JVM, and WasmJS. Any six-digit pairing code works. Try the [playground](/playground/).

## Host-controlled page {#host-controlled-page}

Use `PrivilegeScreen` when the host owns the state and operations. Supply a nonempty `startupModes` list, action callbacks, and localized feedback.

```kotlin
PrivilegeScreen(
    state = screenState,
    actions = hostActions,
    showFeedback = ::showMessage,
    systemPrompt = prompt, // PrivilegeUiPromptState(title, message), or null
    topBar = { AppTopBar() },
)
```

## User tutorial links {#startup-tutorial-links}

The built-in links open the localized [startup tutorial](./startup-tutorial). Set `startupTutorialUrl` to use your own guide or `onViewStartupTutorial` to handle navigation. `onViewPermissionSolutions` takes precedence for permission help.
