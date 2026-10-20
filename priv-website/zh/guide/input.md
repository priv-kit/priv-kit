---
description: 通过系统 Binder 注入 Android 输入，通过设备会话访问 evdev。
---

# 输入 {#input}

输入功能分为两个独立模块：`priv-input` 的 `PrivilegeInput` 使用系统 input 代理 Binder；`priv-evdev` 的 `PrivilegeEvdevController` 在特权进程访问设备节点，并包含原生库。权限由应用提供。模块发布前通过源码接入：

```kotlin
dependencies {
    implementation(project(":priv-input")) // 系统输入
    implementation(project(":priv-evdev")) // 设备输入，按需添加
}
```

按需选择依赖；同时使用两种输入方式时添加两者。公开类型分别位于 `priv.kit.input` 和 `priv.kit.evdev`。

## shellCommand 与 compatShellCommand {#shell-commands}

### 代理 Binder {#proxy}

向 `PrivilegeInput` 传入系统 input 服务的代理 Binder：

```kotlin
// PrivilegeBinderWrapper 来自可选的 Core；也可传其他能转发事务的 IBinder。
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService(Context.INPUT_SERVICE))
val input = PrivilegeInput(binder)
```

代理以具备 `INJECT_EVENTS` 权限的 shell/root 身份转发事件注入。

### shellCommand {#shell-command}

`shellCommand` 在 Android 12+ 直接使用设备自身的输入命令实现，放在工作线程执行：

```kotlin
val output = withContext(Dispatchers.IO) {
    input.shellCommand("-d", rearDisplayId.toString(), "tap", "300", "500")
}
```

参数按项传入，省略 `input` 前缀和 shell 转义。通过 `input.shellCommand("help")` 查看设备支持的参数。返回值合并 stdout/stderr，命令失败抛出 `InputShellCommandException`，保留 `exitCode` 和 `output`；输出读取失败抛出 `IOException`。取消调用方协程不会停止服务端命令；需要可取消手势时使用 suspend 方法。

### compatShellCommand {#compat-shell-command}

跨版本使用同一套命令参数时，调用 `compatShellCommand`。Android 12+ 优先使用设备实现，缺失的命令或参数在本地补齐；Android 8–11 使用本地注入。本地支持[协程函数](#android-input)章节列出的命令，包括新版按键选项和滚动，成功返回空字符串。存在设备命令入口时，未知扩展原样转发。两个命令方法均为同步调用。

```kotlin
withContext(Dispatchers.IO) {
    input.compatShellCommand("keyevent", "--duration", "1000", "A")
}
```

## input 协程函数 {#android-input}

在协程中调用便捷方法。事件生成遵循对应 Android 版本的 AOSP 实现，新选项在旧系统上补齐。同一目标上的手势由应用按顺序执行。

例如，执行五秒滑动，一秒后取消：

```kotlin
val gesture = scope.launch {
    input.swipe(300f, 900f, 300f, 300f, durationMillis = 5_000)
}
delay(1_000)
gesture.cancelAndJoin()
```

取消或中途失败时会尝试发送触摸 CANCEL 或释放已按下的键；遇到 `DeadObjectException` 时跳过释放并抛出原异常。系统注入返回 `false` 时抛出 `IOException`。正在进行的同步 Binder 调用结束后才能完成取消，之后可继续使用同一对象。

以下示例均在协程内调用。手动组合的 `motionEvent` 由调用方维护按下/释放序列，在 `finally` 中通过 `NonCancellable` 完成释放。

点击：

```kotlin
input.tap(300f, 500f)
```

滑动：

```kotlin
input.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
```

按键（返回）：

```kotlin
input.keyEvent(KeyEvent.KEYCODE_BACK)
```

组合键（Ctrl+A）：

```kotlin
input.keyCombination(
    intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A),
)
```

所有方法都接受 `source` 和 `displayId`。通过 `DisplayManager` 获取目标屏幕 ID，坐标使用该屏幕的像素值。例如，在副屏点击：

```kotlin
input.tap(300f, 500f, displayId = rearDisplayId)
```

通过 `source` 指定输入设备类型，例如鼠标滚动：

```kotlin
input.scroll(
    300f, 500f,
    mapOf(MotionEvent.AXIS_VSCROLL to -2f),
    source = InputDevice.SOURCE_MOUSE,
)
```

`source` 默认为 0，使用命令默认值。`displayId` 默认为 -1（`INVALID_DISPLAY`）：指针事件使用主屏 0（`DEFAULT_DISPLAY`），按键及非指针事件由系统路由。其他负数与命令一样归一为 0。没有暴露为 Android 逻辑显示屏的厂商背屏需要厂商接口。

| 子命令 → 方法 | 参数与默认值 |
| --- | --- |
| `text` → `text` | 字符串；`%s` 转为空格。字符范围由虚拟键盘映射决定。默认 source：keyboard。 |
| `keyevent` → `keyEvent` | 单个或一组 Android 按键码（`KeyEvent.KEYCODE_*`），数组按顺序逐个按下并释放。也接受名称（`A`、`KEYCODE_A`、`29`）；`longPress=false`、`durationMillis=0`、`doubleTap=false`、`async=false`、`delayMillis=0`。默认 source 因版本而异，Android 8–9 为 KEYBOARD，Android 10+ 为 UNKNOWN。 |
| `tap` → `tap` | x、y。默认 source：touchscreen。 |
| `swipe` → `swipe` | 起止 x/y，`durationMillis=300`；负时长也按 300 ms 处理。默认 source：touchscreen。 |
| `draganddrop` → `dragAndDrop` | 与 swipe 相同，先执行长按。默认 source：touchscreen。 |
| `press` → `press` | 无专用参数，在 (0, 0) 按下并释放。默认 source：trackball。 |
| `roll` → `roll` | dx、dy，压力为 0 的移动。默认 source：trackball。 |
| `motionevent` → `motionEvent` | `MotionEvent.ACTION_DOWN/UP/MOVE/CANCEL`，DOWN/UP/MOVE 必须提供 x/y，CANCEL 可以省略两者。与命令一样，每次调用使用新的 downTime。默认 source：touchscreen。 |
| `scroll` → `scroll` | 指针 source 必须提供 x/y，其他 source 可省略；`axes: Map<Int, Float>` 默认空，可用 `AXIS_SCROLL`、`AXIS_HSCROLL`、`AXIS_VSCROLL`。非指针 source 忽略 x/y。默认 source：rotaryencoder。 |
| `keycombination` → `keyCombination` | 至少两个按键码/名称。`durationMillis=0` 使用系统默认时长；缺失的时长支持由本地补齐。始终异步注入。AOSP 在 source 为 0 时保留 UNKNOWN。 |

source 使用 `InputDevice.SOURCE_*`：`KEYBOARD`、`DPAD`、`GAMEPAD`、`TOUCHSCREEN`、`MOUSE`、`STYLUS`、`TRACKBALL`、`TOUCHPAD`、`TOUCH_NAVIGATION`、`JOYSTICK` 或 `ROTARY_ENCODER`。其中 `TOUCH_NAVIGATION` 和 `ROTARY_ENCODER` 对应 CLI 的 `touchnavigation` 和 `rotaryencoder`。

Android 8–17 按版本生成 AOSP 事件；16+ 使用 120 Hz 滑动节奏，更早版本不额外限频。指定其他屏幕需要 Android 10+；滑动和拖动的计时差异见[架构说明](https://github.com/priv-kit/priv-kit/blob/main/docs/architecture.md#输入)。

坐标和轴值必须有限。滑动、拖拽时长必须在有符号 32 位整数范围内，组合键时长为 0–2147483647 ms。正数按键时长与 `longPress` 二选一。

## evdev 设备输入（实验性） {#evdev}

通过 `PrivilegeEvdevController` 操作 `/dev/input/eventN`。触摸手势使用相同的协程取消写法，跨 UserService 取消也会等待设备触点释放。查询和原始写入为同步方法，放在工作线程调用。

### 查询设备 {#inspect}

在具备节点访问权限的进程中创建设备控制器：

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

`openDevice` 默认只读，访问受节点权限和 SELinux 限制。目录枚举失败抛出 `IOException`。

`queryCapabilities(0)` 返回事件类型，其他类型返回支持的事件代码。
`queryAxis(code)` 返回当前值、最小值、最大值、fuzz、flat 和 resolution。
`queryTrackingIds()` 查询最多 256 个 Type B slot，-1 表示未激活。不支持的查询通过 errno 报错。

### 设备触摸手势 {#device-touch}

通过 `openTouchDevice(displayId = Display.DEFAULT_DISPLAY)` 打开设备，即可执行 evdev
`tap`、`swipe` 和 `dragAndDrop`。在特权进程中使用
`PrivilegeEvdevController.createLocal(context)` 创建本地控制器，由库查找唯一关联的触摸节点：

```kotlin
devices.openTouchDevice(displayId).use { device ->
    device.tap(300f, 500f)
    device.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
    device.dragAndDrop(300f, 500f, 600f, 800f,
        durationMillis = 300, longPressMillis = 600)
}
```

返回的 `PrivilegeEvdevTouchscreen` 提供 `displayId`、三个手势方法和 `close()`。设备以可写方式打开，自动读取完整逻辑尺寸和旋转，并在设备或屏幕变化后重新匹配。关联缺失、有多个候选或屏幕不可用时会报错，下一次显式调用可重新查询。如果逻辑屏幕重建后编号改变，需要打开新句柄。

所选输入设备或目标屏幕的存在状态、尺寸、旋转、亮灭状态变化时，手势停止并尝试释放触点，后续调用会重新匹配设备。

按节点操作仍使用 `openTouchDevice(path, displayId)`，返回 `PrivilegeEvdevDevice`。
它固定使用该节点，每次手势刷新尺寸和旋转，并提供原始查询、写入和手动映射。

厂商设备需要调整轴方向或手动指定尺寸时，可以覆盖固定节点句柄的映射：

```kotlin
device.configureTouch(PrivilegeEvdevTouchMapping(
    width, height, rotation, swapAxes = false, invertX = false, invertY = false,
))
```

这会关闭该句柄的自动刷新和屏幕变化检测。也可继续使用 `openDevice(path, writable = true)`
加 `configureTouch`，此时不带 Context 的 `createLocal()` 仍然适用。
手动 width/height 是当前屏幕完整像素尺寸，rotation 使用 `Surface.ROTATION_*`。映射先还原旋转，
再按需交换、反转轴，最后缩放到设备范围。坐标必须有限且位于 0..width-1 / 0..height-1。
屏幕变化后需更新手动映射。

库自动识别 Type B、Type A 或单点触摸，生成触点和同步事件。滑动默认 300 ms，负数时也取
300 ms。拖动默认先保持 500 ms，再移动 300 ms，两个时长分别配置。
有效时长不能超过有符号 32 位整数范围。

手势期间保持设备独占，避免物理触摸和其他句柄同时写入。原始写入结束后，先释放触点再调用手势。

帧写入失败或部分写入时停止手势，尝试释放触点并保留原始错误。释放失败后禁止新手势；关闭设备时会在释放 fd 前尝试清理。进程终止或持续写入失败可能导致清理无法完成。原始写入创建的触点由调用方释放。

### 查询触摸设备关联 {#touch-devices}

`queryTouchDevices()` 返回触摸节点的 `path`、`name`、`inputDeviceId` 和 `displayId`。
在有输入服务 dump 权限的进程中使用 `createLocal(context)` 创建控制器。

```kotlin
val devices = controller.queryTouchDevices()
val selected = devices.single { it.displayId == targetDisplayId }
controller.openTouchDevice(selected.path, targetDisplayId).use { device ->
    device.tap(300f, 500f)
}
```

一个屏幕可能关联多个触摸节点，有歧义时需显式选择。编号为 null 表示关联未知，0 表示主屏。缺少 dump 权限或无法识别格式时抛出异常；厂商格式或关联冲突可能导致编号未知。结果是快照，设备或屏幕变化后需重新查询。

### 提交原始事件 {#write}

显式选择设备并以 `writable = true` 打开，调用 `device.writeEvents(events)`，每批 1–256 个
`PrivilegeEvdevEvent(type, code, value)`。代码和坐标使用 Linux 设备值。需要时显式加入 `EV_SYN / SYN_REPORT`。调用方负责 slot、按键状态、tracking ID、
释放事件和失败恢复。

每批执行一次非阻塞 write。`PrivilegeEvdevWriteResult` 包含：

| 字段 | 含义 |
| --- | --- |
| `requestedEventCount` | 本批事件数 |
| `submittedEventCount` | write 接受的事件数，未知时为 null |
| `errno` | 可用时提供 Linux 写入错误 |
| `isComplete` | 整批已提交，不代表已交付 UI |

系统调用失败时保守报告未知提交数。Binder 回复丢失也可能掩盖已成功的写入；不要重放部分写入或结果未知的批次。

### 生命周期与 UserService 接入 {#lifetime}

主进程控制设备时，在特权 UserService 创建控制器，通过自己的 AIDL 方法 `IBinder getDeviceController()` 返回 Binder（AIDL 需导入 `android.os.IBinder`）：

```kotlin
// 位于自己的 UserService 实现中。
private val input = PrivilegeEvdevController.createLocal(context)

override fun getDeviceController(): IBinder = input.asBinder()

override fun destroy() {
    input.close()
    // 清理应用自己的其他资源。
}
```

主进程连接 UserService 后使用设备控制器：

```kotlin
PrivilegeEvdevController.fromBinder(userService.getDeviceController()).use { input ->
    input.openTouchDevice(displayId).use { it.tap(300f, 500f) }
}
```

两端必须使用相同版本的库。每个设备控制器接受一个远程 owner，固定首次调用方的 UID/PID；只向可信调用方交付 Binder。独立 owner 使用独立控制器，UserService 销毁时关闭本地控制器。

每个会话最多打开 64 个设备，枚举最多 1024 条路径。同一句柄的调用和清理串行执行，其他句柄仍可使用。远程手势占用一个 Binder 线程，取消通过独立 Binder 信号传递；`cancelAndJoin` 等待手势和触点清理完成。

通过 `use` 或 `close` 释放设备句柄。控制器关闭或 owner 死亡会取消手势并释放会话设备；单独关闭设备会停止手势并等待清理。关闭失败后普通操作仍被禁用，但可显式重试 `close()`；确认关闭后重复调用无效。进程生命周期由应用管理。

设备系统调用失败使用带 errno 的 `PrivilegeEvdevSyscallException`；手势写入失败使用 `PrivilegeEvdevWriteException`，其 `result` 保留请求数量、已提交数量和 errno。屏幕不可用、设备匹配失败或配置变化使用带 `displayId` 的 `PrivilegeEvdevTargetException`。前两者继承 `IOException`，目标异常直接继承 `Exception`。其他读写失败使用 `IOException`，Binder 通信错误使用 `RemoteException`。

`priv-sample` 演示代理输入和设备操作，服务接入见 [UserService](./user-service)。

## 两种输入方式对比 {#comparison}

输入有两种实现方式：通过 `InputManager.injectInputEvent` 注入 Android 事件，或向 `/dev/input/eventN` 写入 Linux 输入事件。

| 对比 | 系统输入：`PrivilegeInput` | 设备输入：`PrivilegeEvdevController` |
| --- | --- | --- |
| 事件路径 | 注入 `KeyEvent`、`MotionEvent`，由 Android 分发 | 写入 evdev 节点，由 Android 读取设备事件后分发 |
| 应用识别 | 应用可根据虚拟设备 ID 等事件特征识别或筛选模拟输入 | 事件经真实设备节点进入 InputReader，使用该设备身份，避开系统注入路径的这类特征 |
| 权限与接入 | 使用 shell/root 身份的系统 input Binder，可直接接入代理 Binder | 执行进程需要节点读写权限；主进程可通过 UserService 控制设备 |
| 优点 | 支持按键、文本、触摸、滚动和组合键；使用 Android 坐标与 displayId，接入简单 | 可查询设备能力、轴和触点状态，并控制 Linux 原始事件序列 |
| 代价 | 受系统注入权限、输入路由和目标应用处理规则影响 | 受节点权限和 SELinux 限制；需要处理触摸协议、坐标映射及设备与屏幕关联 |
| 库的封装 | 提供常用命令 | 提供设备查询、原始写入，以及触摸设备的 tap、swipe、dragAndDrop |

两条路径的重要区别是应用看到的事件来源：AOSP Android 8–10 保留注入事件传入的 `deviceId`；从 [Android 11](https://android.googlesource.com/platform/frameworks/native/+/refs/tags/android-11.0.0_r1/services/inputflinger/dispatcher/InputDispatcher.cpp) 开始，常规 `injectInputEvent` 强制将其改为虚拟设备 ID `-1`，应用可据此筛选输入。evdev 写入沿真实设备输入链路处理，使用对应设备身份。

一般的点击、滑动和按键优先使用 `PrivilegeInput`。需要设备级查询或控制原始事件时，选择 `PrivilegeEvdevController`；其触摸封装会处理支持的协议和坐标映射。evdev 写入取决于节点文件权限和 SELinux 策略：shell 满足两者即可写入；root 通常可绕过文件权限检查，仍受 SELinux 限制。Permissive 模式只放宽 SELinux，文件权限仍生效。系统输入注入、设备查询和节点写入各有权限要求。

`PrivilegeInput` 的协程函数在本地生成 Android 事件。evdev 的 Android 屏幕路由由系统根据设备关联决定，库的 `displayId` 用于查找关联节点和转换坐标。
