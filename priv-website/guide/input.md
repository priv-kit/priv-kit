---
description: Android input through a system Binder, and evdev access through a device session.
---

# Input {#input}

Input has two independent modules: `PrivilegeInput` in `priv-input` uses a system input-service proxy Binder; `PrivilegeEvdevController` in `priv-evdev` accesses device nodes in a privileged process and includes the native library. The app supplies privileges. Until publication, use the source modules:

```kotlin
dependencies {
    implementation(project(":priv-input")) // System input
    implementation(project(":priv-evdev")) // Device input, when needed
}
```

Choose either dependency, or both when needed. Public types use `priv.kit.input` and `priv.kit.evdev`, respectively.

## shellCommand and compatShellCommand {#shell-commands}

### Proxy Binder {#proxy}

Pass a system input-service proxy Binder to `PrivilegeInput`:

```kotlin
// PrivilegeBinderWrapper comes from optional Core; other forwarding IBinder implementations work too.
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService(Context.INPUT_SERVICE))
val input = PrivilegeInput(binder)
```

The proxy forwards event injection with shell/root identity and `INJECT_EVENTS` permission.

### shellCommand {#shell-command}

`shellCommand` runs the device's own input command implementation on Android 12+. Run it on a worker thread:

```kotlin
val output = withContext(Dispatchers.IO) {
    input.shellCommand("-d", rearDisplayId.toString(), "tap", "300", "500")
}
```

Arguments are separate tokens, without an `input` prefix or shell escaping. Use `input.shellCommand("help")` to see the device's parameters. The return value combines stdout/stderr; command failures throw `InputShellCommandException`, retaining `exitCode` and `output`. Output read failures throw `IOException`. Cancelling the caller does not stop a device-side command; use the suspend methods for cancellable gestures.

### compatShellCommand {#compat-shell-command}

Use `compatShellCommand` for the same command syntax across versions. On Android 12+ it prefers the device implementation and fills missing commands/options locally; Android 8–11 uses local injection. Local execution supports the commands listed under [coroutine input functions](#android-input), including newer key options and scrolling, and returns an empty string on success. Unknown extensions are forwarded when a device command service is available. Both command methods are synchronous.

```kotlin
withContext(Dispatchers.IO) {
    input.compatShellCommand("keyevent", "--duration", "1000", "A")
}
```

## Coroutine input functions {#android-input}

Call convenience methods from a coroutine. They generate events following the corresponding AOSP version, with newer options supplied on older systems. Sequence gestures targeting the same destination in your app.

To cancel a five-second swipe after one second:

```kotlin
val gesture = scope.launch {
    input.swipe(300f, 900f, 300f, 300f, durationMillis = 5_000)
}
delay(1_000)
gesture.cancelAndJoin()
```

Cancellation or a mid-operation failure attempts to send touch CANCEL or release held keys. A `DeadObjectException` skips cleanup and propagates unchanged. When system injection returns `false`, it throws `IOException`. An in-flight synchronous Binder call finishes before cancellation can complete. Subsequent gestures can reuse the object.

The following examples run inside a coroutine. Manually sequenced `motionEvent` calls remain caller-managed; release them in `finally` with `NonCancellable`.

Tap:

```kotlin
input.tap(300f, 500f)
```

Swipe:

```kotlin
input.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
```

Key press (Back):

```kotlin
input.keyEvent(KeyEvent.KEYCODE_BACK)
```

Key combination (Ctrl+A):

```kotlin
input.keyCombination(
    intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A),
)
```

Every method accepts `source` and `displayId`. Obtain the target display ID from `DisplayManager` and use coordinates in its pixels. For example, tap on a secondary display:

```kotlin
input.tap(300f, 500f, displayId = rearDisplayId)
```

Set `source` to select the input device type, for example mouse scrolling:

```kotlin
input.scroll(
    300f, 500f,
    mapOf(MotionEvent.AXIS_VSCROLL to -2f),
    source = InputDevice.SOURCE_MOUSE,
)
```

`source` defaults to 0, selecting the command default. `displayId` defaults to -1 (`INVALID_DISPLAY`): pointer events use main display 0 (`DEFAULT_DISPLAY`), while the system routes key and non-pointer events. Other negative IDs normalize to 0, as in the command. Manufacturer-only screens that are not Android logical displays need vendor support.

| Command → method | Arguments and defaults |
| --- | --- |
| `text` → `text` | String; `%s` becomes a space. Character support follows the virtual keyboard mapping. Default source: keyboard. |
| `keyevent` → `keyEvent` | One Android key code (`KeyEvent.KEYCODE_*`) or an array pressed and released sequentially. Also accepts names (`A`, `KEYCODE_A`, `29`). `longPress=false`, `durationMillis=0`, `doubleTap=false`, `async=false`, `delayMillis=0`. Default source varies by version; KEYBOARD on Android 8–9, UNKNOWN on Android 10+. |
| `tap` → `tap` | x, y. Default source: touchscreen. |
| `swipe` → `swipe` | Start/end x/y, `durationMillis=300`; negative duration also selects 300 ms. Default source: touchscreen. |
| `draganddrop` → `dragAndDrop` | Same arguments as swipe, with an initial long press. Default source: touchscreen. |
| `press` → `press` | No command-specific arguments; tap at (0, 0). Default source: trackball. |
| `roll` → `roll` | dx, dy; zero-pressure move. Default source: trackball. |
| `motionevent` → `motionEvent` | `MotionEvent.ACTION_DOWN/UP/MOVE/CANCEL`, x/y required for DOWN/UP/MOVE; CANCEL may omit both. Each call starts a new event downTime, matching the command. Default source: touchscreen. |
| `scroll` → `scroll` | x/y required for pointer sources and optional otherwise; `axes: Map<Int, Float>` (default empty); accepts `AXIS_SCROLL`, `AXIS_HSCROLL`, `AXIS_VSCROLL`. Non-pointer sources ignore x/y. Default source: rotary encoder. |
| `keycombination` → `keyCombination` | At least two codes/names. Omitted or `null` duration uses the version default; explicit `durationMillis=0` uses timed-chord semantics with no hold. Missing duration support is supplied locally. Always injects asynchronously; source 0 remains UNKNOWN in AOSP. |

Use `InputDevice.SOURCE_*`: `KEYBOARD`, `DPAD`, `GAMEPAD`, `TOUCHSCREEN`, `MOUSE`, `STYLUS`, `TRACKBALL`, `TOUCHPAD`, `TOUCH_NAVIGATION`, `JOYSTICK`, or `ROTARY_ENCODER`. `TOUCH_NAVIGATION` and `ROTARY_ENCODER` map to `touchnavigation` and `rotaryencoder` on the CLI.

Android 8–17 event generation follows version-specific AOSP behavior. Android 16+ adds 120 Hz swipe pacing; earlier versions inject moves without an added pacing delay. Targeting other displays requires Android 10+. See the [architecture notes](https://github.com/priv-kit/priv-kit/blob/main/docs/architecture.md#输入) for swipe and drag timing differences.

Coordinates and axis values must be finite. Swipe/drag durations must fit a signed 32-bit integer; chord durations must be 0–2147483647 ms. Choose either a positive key duration or `longPress`.

## Evdev device input (experimental) {#evdev}

Use `PrivilegeEvdevController` to access `/dev/input/eventN`. Touch gestures use the same coroutine cancellation pattern and wait for contact release, including across UserService. Queries and raw writes are synchronous; run them on a worker thread.

### Inspect devices {#inspect}

Create a device controller in the process with access to the nodes:

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

`openDevice` defaults to read-only. Node permissions and SELinux govern access. Directory enumeration failures throw `IOException`.

`queryCapabilities(0)` returns event types; another type returns its supported codes.
`queryAxis(code)` returns the current value, minimum, maximum, fuzz, flat and resolution.
`queryTrackingIds()` queries Type B slots (up to 256): -1 means inactive. Unsupported queries
fail with errno.

### Device touch gestures {#device-touch}

Use `openTouchDevice(displayId = Display.DEFAULT_DISPLAY)` for evdev `tap`, `swipe`, and
`dragAndDrop`. Create the local controller with `PrivilegeEvdevController.createLocal(context)`
in the privileged process. The library selects the uniquely associated touch node:

```kotlin
devices.openTouchDevice(displayId).use { device ->
    device.tap(300f, 500f)
    device.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
    device.dragAndDrop(300f, 500f, 600f, 800f,
        durationMillis = 300, longPressMillis = 600)
}
```

The returned `PrivilegeEvdevTouchscreen` exposes `displayId`, the three gestures, and `close()`. It opens writable, reads full logical dimensions and rotation, and re-matches after device/display changes. Missing or ambiguous associations and unavailable displays fail; the next explicit call can query again. If a logical display is recreated with a new ID, open a new handle.

Changes to the selected input device or the target display's presence, dimensions, rotation, or on/off state stop the gesture and attempt contact release. Subsequent calls re-match the device.

For a fixed node, retain `openTouchDevice(path, displayId)`, which returns `PrivilegeEvdevDevice`.
It keeps the selected node, refreshes dimensions and rotation for each gesture, and provides raw queries/writes and manual mapping.

For vendor-specific axis orientation or manual geometry, override the fixed node's mapping:

```kotlin
device.configureTouch(PrivilegeEvdevTouchMapping(
    width, height, rotation, swapAxes = false, invertX = false, invertY = false,
))
```

This disables automatic display refresh and change detection for that handle. `openDevice(path,
writable = true)` followed by `configureTouch` also works with the no-Context `createLocal()`.
Manual width/height are full current-display pixel dimensions; rotation uses `Surface.ROTATION_*`.
Mapping undoes rotation, swaps axes if requested, applies inversions, and scales to device ranges.
Coordinates must be finite and within 0..width-1 / 0..height-1. Update manual mappings after display
changes.

The library detects Type B, Type A, or single-touch axes and emits contact and sync events.
Swipe defaults to 300 ms; a negative duration also selects 300 ms. Drag movement defaults
to 300 ms after a separate 500 ms hold; hold and movement durations are configured independently.
Durations must fit a nonnegative signed 32-bit value.

Keep the device exclusive during gestures, avoiding physical touches and writes through other handles. Release raw-write contacts before using gestures.

Failed or partial frames stop the gesture. The library attempts contact release and preserves the original failure. If release fails, new gestures are blocked; close the device to attempt cleanup before its fd is released. Process termination or continued write failures can prevent cleanup. Raw-write contacts remain the caller's responsibility.

### Query touch-device associations {#touch-devices}

`queryTouchDevices()` returns touch nodes with `path`, `name`, `inputDeviceId`, and `displayId`.
Use a controller created with `createLocal(context)` in a process allowed to dump the input service.

```kotlin
val devices = controller.queryTouchDevices()
val selected = devices.single { it.displayId == targetDisplayId }
controller.openTouchDevice(selected.path, targetDisplayId).use { device ->
    device.tap(300f, 500f)
}
```

A display can have several touch nodes; select one explicitly if the list is ambiguous. Null IDs mean an unknown association; display 0 is the main display. Missing dump permission or an unrecognized format throws; vendor formats or conflicting associations can leave IDs unknown. Results are snapshots: query again after device/display changes.

### Submit raw events {#write}

Open an explicitly selected node with `writable = true`, then call `device.writeEvents(events)`
with 1–256 `PrivilegeEvdevEvent(type, code, value)` values. Codes and coordinates are Linux
device values. Include `EV_SYN / SYN_REPORT`
explicitly where needed. The caller owns slot/key state, tracking IDs, releases and recovery.

Each batch makes one nonblocking write syscall. `PrivilegeEvdevWriteResult` has:

| Field | Meaning |
| --- | --- |
| `requestedEventCount` | Number of events in this batch |
| `submittedEventCount` | Count accepted by write, or null when unknown |
| `errno` | Linux write error when available |
| `isComplete` | The entire batch was submitted, not proof of UI delivery |

Syscall errors conservatively report an unknown submitted count. A lost Binder reply can hide a successful write; do not replay partial or unknown results.

### Lifetime and UserService integration {#lifetime}

For main-process device access, create a controller in a privileged UserService and export it through your AIDL method `IBinder getDeviceController()` (import `android.os.IBinder`):

```kotlin
// Inside your UserService implementation.
private val input = PrivilegeEvdevController.createLocal(context)

override fun getDeviceController(): IBinder = input.asBinder()

override fun destroy() {
    input.close()
    // Clean up other application-owned resources.
}
```

After connecting to the UserService, the main process can use the device controller:

```kotlin
PrivilegeEvdevController.fromBinder(userService.getDeviceController()).use { input ->
    input.openTouchDevice(displayId).use { it.tap(300f, 500f) }
}
```

Both processes must use the same library version. Each device controller accepts one remote owner, fixed to the first caller's UID/PID; export it only to trusted callers. Create separate controllers for independent owners. Close the local controller when the UserService is destroyed.

A session allows 64 open devices and enumeration of up to 1024 paths. Calls and cleanup are serialized per handle; other handles remain usable. A remote gesture occupies one Binder thread. Cancellation uses a separate Binder signal; `cancelAndJoin` waits for the gesture and contact cleanup to finish.

Use `use` or `close` for device handles. Controller close or owner death cancels gestures and releases all session devices; closing an individual device stops its gesture and waits for cleanup. After a close failure, normal operations stay disabled but explicit `close()` retries are allowed; confirmed closes are no-ops. The app manages process lifetime.

Device syscall failures use `PrivilegeEvdevSyscallException` with errno. Gesture write failures use `PrivilegeEvdevWriteException`; its `result` retains requested and submitted event counts and errno. An unavailable display, failed device match, or configuration change uses `PrivilegeEvdevTargetException` with `displayId`. The first two extend `IOException`; the target exception extends `Exception` directly. Other I/O failures use `IOException`, and Binder communication failures use `RemoteException`.

See `priv-sample` for proxy input and device operations, and [UserService](./user-service) for service integration.

## Comparing the input paths {#comparison}

There are two input paths: inject Android events through `InputManager.injectInputEvent`, or write Linux input events to `/dev/input/eventN`.

| Comparison | System input: `PrivilegeInput` | Device input: `PrivilegeEvdevController` |
| --- | --- | --- |
| Event path | Inject `KeyEvent` and `MotionEvent` for Android to dispatch | Write evdev nodes for Android to read and dispatch as device events |
| App detection | Apps can identify or filter simulated input using event characteristics such as a virtual device ID | Events enter InputReader through the actual device node and use its identity, avoiding these system-injection-path characteristics |
| Permissions and access | Use a system input Binder with shell/root identity, including a forwarding proxy Binder | The executing process needs node read/write access; the main process can control devices through a UserService |
| Advantages | Keys, text, touch, scrolling, and chords; Android coordinates and display IDs make integration straightforward | Query device capabilities, axes, and contact state, and control raw Linux event sequences |
| Tradeoffs | Subject to system injection permissions, input routing, and target-app handling | Subject to node permissions and SELinux; requires touch protocols, coordinate mapping, and device/display association |
| Library support | Convenience commands | Device queries, raw writes, and touchscreen tap, swipe, and dragAndDrop |

The paths differ in the event origin visible to apps: AOSP Android 8–10 preserves the supplied `deviceId` for injected events; starting with [Android 11](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-11.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp), ordinary `injectInputEvent` calls force it to the virtual device ID `-1`, which apps can use to filter input. Evdev writes follow the physical device input path and use the corresponding device identity.

Prefer `PrivilegeInput` for ordinary taps, swipes, and key presses. Choose `PrivilegeEvdevController` for device queries or raw event control; its touch helpers handle supported protocols and coordinate mapping. Evdev writes depend on node file permissions and SELinux policy: shell can write when both allow access; root normally bypasses file permission checks but remains subject to SELinux. Permissive mode relaxes SELinux enforcement while file permissions still apply. System input injection, device queries, and node writes have separate permission requirements.

`PrivilegeInput` coroutine functions generate Android events locally. For evdev, Android routes input according to device associations; the library uses `displayId` to select an associated node and map coordinates.
