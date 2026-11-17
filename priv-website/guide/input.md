---
description: Android input through a system Binder, and evdev access through a device session.
---

# Input {#input}

Use `priv-input` for Android events, or `priv-evdev` for device access. Add only the source modules your app uses.

```kotlin
dependencies {
    implementation(project(":priv-input")) // System input
    implementation(project(":priv-evdev")) // Device input, when needed
}
```

## shellCommand and compatShellCommand {#shell-commands}

### Proxy Binder {#proxy}

Pass a system input-service Binder with shell or root permissions. The example uses `priv-core` to obtain it.

```kotlin
// PrivilegeBinderWrapper comes from optional Core; other forwarding IBinder implementations work too.
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService(Context.INPUT_SERVICE))
val input = PrivilegeInput(binder)
```

### shellCommand {#shell-command}

Run the device’s `input` command on Android 12 or later. Call from a worker thread, pass each argument separately, and omit the `input` prefix.

```kotlin
val output = withContext(Dispatchers.IO) {
    input.shellCommand("-d", rearDisplayId.toString(), "tap", "300", "500")
}
```

Use `input.shellCommand("help")` to see supported arguments. Command failures throw `InputShellCommandException` with exit code and output.

### compatShellCommand {#compat-shell-command}

Use `compatShellCommand()` for Android 8 or later. It fills in missing commands and options locally. Call it from a worker thread. Use suspend functions when you need cancellation.

```kotlin
withContext(Dispatchers.IO) {
    input.compatShellCommand("keyevent", "--duration", "1000", "A")
}
```

## Coroutine input functions {#android-input}

Use suspend functions for taps, swipes, keys, and scrolls. Pass `displayId` for a secondary display on Android 10 or later, and use that display’s pixel coordinates.

```kotlin
input.tap(300f, 500f, durationMillis = 150)
input.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
input.keyEvent(KeyEvent.KEYCODE_BACK)
input.tap(300f, 500f, displayId = rearDisplayId)
```

Cancel a gesture through its coroutine. The helper attempts to release active contacts or keys. If you send individual `motionEvent()` calls, you manage the corresponding release.

## Evdev device input (experimental) {#evdev}

`PrivilegeEvdevController` accesses device nodes from a process with the required filesystem and SELinux permissions. Run synchronous queries and writes on a worker thread.

### Inspect devices {#inspect}

Use `createLocal()` to list and inspect devices in the privileged process. `openDevice()` defaults to read-only.

```kotlin
import priv.kit.evdev.PrivilegeEvdevController

fun inspectInput() {
    PrivilegeEvdevController.createLocal().use { input ->
        for (path in input.listDevices()) {
            input.openDevice(path).use { device ->
                println("$path: ${device.queryName()}")
                println(device.queryCapabilities())
            }
        }
    }
}
```

### Device touch gestures {#device-touch}

Create the controller with `createLocal(context)`. `openTouchDevice(displayId)` selects the touch node for that Android display. Open a fresh handle if the display ID changes.

```kotlin
devices.openTouchDevice(displayId).use { device ->
    device.tap(300f, 500f)
    device.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
    device.dragAndDrop(300f, 500f, 600f, 800f,
        durationMillis = 300, longPressMillis = 600)
}
```

If device selection is ambiguous, pick an explicit path with `openTouchDevice(path, displayId)`. For manual orientation and dimensions, configure that handle with `configureTouch(...)` and update the mapping when the display changes. Avoid physical touches or other writers during a gesture.

### Query touch-device associations {#touch-devices}

Use `queryTouchDevices()` to inspect associations before choosing a node. It requires a Context-backed controller and permission to dump the input service.

```kotlin
val devices = controller.queryTouchDevices()
val selected = devices.single { it.displayId == targetDisplayId }
controller.openTouchDevice(selected.path, targetDisplayId).use { device ->
    device.tap(300f, 500f)
}
```

A null `displayId` means the association is unknown.

### Submit raw events {#write}

Open the node with `writable = true` and call `writeEvents()` with 1–256 `PrivilegeEvdevEvent` values. The caller manages slots, tracking IDs, release events, and `SYN_REPORT`.

Check the returned `isComplete` and `errno`. A partial or unknown result can already have affected the device, so avoid retrying the entire batch.

### Lifetime and UserService integration {#lifetime}

Create and close the local controller in your privileged UserService. Export it through an AIDL method returning `IBinder`.

```kotlin
// Inside your UserService implementation.
private val input = PrivilegeEvdevController.createLocal(context)

override fun getDeviceController(): IBinder = input.asBinder()

override fun destroy() {
    input.close()
    // Clean up other application-owned resources.
}
```

```kotlin
PrivilegeEvdevController.fromBinder(userService.getDeviceController()).use { input ->
    input.openTouchDevice(displayId).use { it.tap(300f, 500f) }
}
```

Use the same library version in both processes. Each controller accepts one remote owner. Use `use` or `close()` for handles. Cancelling a touch gesture waits for contact cleanup.

## Comparing the input paths {#comparison}

Choose `PrivilegeInput` for ordinary taps, swipes, keys, and text. Choose `PrivilegeEvdevController` to inspect devices or send raw Linux events. Device access depends on file permissions and SELinux.
