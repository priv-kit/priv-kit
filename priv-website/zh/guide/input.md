---
description: 通过系统 Binder 注入 Android 输入，通过设备会话访问 evdev。
---

# 输入 {#input}

用 `priv-input` 注入 Android 事件，用 `priv-evdev` 访问输入设备。按需添加源码模块。

```kotlin
dependencies {
    implementation(project(":priv-input")) // 系统输入
    implementation(project(":priv-evdev")) // 设备输入，按需添加
}
```

## shellCommand 与 compatShellCommand {#shell-commands}

### 代理 Binder {#proxy}

传入具备 shell 或 root 权限的系统 input Binder。示例通过 `priv-core` 获取它。

```kotlin
// PrivilegeBinderWrapper 来自可选的 Core；也可传其他能转发事务的 IBinder。
val binder = checkNotNull(PrivilegeBinderWrapper.fromSystemService(Context.INPUT_SERVICE))
val input = PrivilegeInput(binder)
```

### shellCommand {#shell-command}

在 Android 12 及以上运行设备自带的 `input` 命令。请在后台线程调用，逐个传入参数，省略 `input` 前缀。

```kotlin
val output = withContext(Dispatchers.IO) {
    input.shellCommand("-d", rearDisplayId.toString(), "tap", "300", "500")
}
```

用 `input.shellCommand("help")` 查看设备支持的参数。命令失败会抛出含退出码和输出的 `InputShellCommandException`。

### compatShellCommand {#compat-shell-command}

Android 8 及以上可用 `compatShellCommand()`，缺少的命令和选项由本地实现补齐。在后台线程调用。需要取消手势时，使用挂起函数。

```kotlin
withContext(Dispatchers.IO) {
    input.compatShellCommand("keyevent", "--duration", "1000", "A")
}
```

## input 协程函数 {#android-input}

用挂起函数执行点击、滑动、按键和滚动。Android 10 及以上可传入 `displayId` 操作副屏，坐标使用目标屏幕的像素。

```kotlin
input.tap(300f, 500f, durationMillis = 150)
input.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
input.keyEvent(KeyEvent.KEYCODE_BACK)
input.tap(300f, 500f, displayId = rearDisplayId)
```

取消所属协程即可取消手势。辅助方法会尝试释放触点或按键。逐个调用 `motionEvent()` 时，由调用方负责发送释放事件。

## evdev 设备输入（实验性） {#evdev}

`PrivilegeEvdevController` 在具备设备节点文件权限和 SELinux 访问权限的进程中使用。同步查询和写入应放在后台线程。

### 查询设备 {#inspect}

在特权进程中用 `createLocal()` 列出并检查设备。`openDevice()` 默认以只读方式打开。

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

### 设备触摸手势 {#device-touch}

用 `createLocal(context)` 创建控制器。`openTouchDevice(displayId)` 为该 Android 屏幕选择触摸节点。屏幕 ID 变化后重新打开。

```kotlin
devices.openTouchDevice(displayId).use { device ->
    device.tap(300f, 500f)
    device.swipe(300f, 900f, 300f, 300f, durationMillis = 500)
    device.dragAndDrop(300f, 500f, 600f, 800f,
        durationMillis = 300, longPressMillis = 600)
}
```

无法唯一匹配时，用 `openTouchDevice(path, displayId)` 指定节点。需要手动设置方向和尺寸时，对该句柄调用 `configureTouch(...)`，屏幕变化后更新映射。手势期间避免物理触摸或其他写入。

### 查询触摸设备关联 {#touch-devices}

用 `queryTouchDevices()` 查询节点与屏幕的关联。控制器需要用 Context 创建，进程需有权转储系统 input 服务。

```kotlin
val devices = controller.queryTouchDevices()
val selected = devices.single { it.displayId == targetDisplayId }
controller.openTouchDevice(selected.path, targetDisplayId).use { device ->
    device.tap(300f, 500f)
}
```

`displayId` 为空表示屏幕归属未知。

### 提交原始事件 {#write}

用 `writable = true` 打开节点，再用 `writeEvents()` 提交 1–256 个 `PrivilegeEvdevEvent`。由调用方管理 slot、tracking ID、释放事件和 `SYN_REPORT`。

检查返回的 `isComplete` 和 `errno`。部分写入或结果未知时，设备可能已收到事件，避免重试整批。

### 生命周期与 UserService 接入 {#lifetime}

在特权 UserService 里创建和关闭本地控制器，通过返回 `IBinder` 的 AIDL 方法导出。

```kotlin
// 位于自己的 UserService 实现中。
private val input = PrivilegeEvdevController.createLocal(context)

override fun getDeviceController(): IBinder = input.asBinder()

override fun destroy() {
    input.close()
    // 清理应用自己的其他资源。
}
```

```kotlin
PrivilegeEvdevController.fromBinder(userService.getDeviceController()).use { input ->
    input.openTouchDevice(displayId).use { it.tap(300f, 500f) }
}
```

两端使用同版本库。每个控制器接受一个远程调用方。用 `use` 或 `close()` 释放句柄。取消触摸手势会等待触点清理完成。

## 两种输入方式对比 {#comparison}

普通点击、滑动、按键和文字输入用 `PrivilegeInput`。查询设备或发送原始 Linux 事件用 `PrivilegeEvdevController`，设备访问受文件权限和 SELinux 限制。
