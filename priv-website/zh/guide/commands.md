---
description: 运行命令并读取输出。
---

# 命令执行 {#commands}

在已连接的服务端运行命令，分别读取标准输出和错误输出。

## 启动进程 {#start-process}

直接传入可执行文件和参数。需要 Shell 语法时，显式调用 `/system/bin/sh -c`。

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        arguments = listOf("/system/bin/id"),
        environment = mapOf("LANG" to "C"),
        workingDirectory = "/data/local/tmp",
    ),
)
```

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        listOf("/system/bin/sh", "-c", "for i in 1 2 3; do echo \$i; sleep 1; done"),
    ),
)
```

## 读取输出 {#consume-once}

用 `awaitResult()` 获取退出后的输出，或用 `stream()` 实时读取。每个进程只能消费一次。

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

```kotlin
process.use {
    it.stream().collect { event ->
        when (event) {
            is PrivilegeCommandEvent.Stdout -> showStdout(event.bytes)
            is PrivilegeCommandEvent.Stderr -> showStderr(event.bytes)
            is PrivilegeCommandEvent.Exited -> showExitCode(event.exitCode)
        }
    }
}
```

捕获模式默认每路最多保留 1 MiB。检查 `stdoutTruncated` 和 `stderrTruncated`，或设置 `maxBytesPerStream`。实时输出按字节块传递，显示文本时请增量解码。非零退出码也作为结果返回。

## 超时和取消 {#timeout-cancellation}

默认运行时限为 30 秒。可延长或传 `null` 关闭时限。取消、关闭句柄、宿主退出或服务端关闭都会终止未结束的命令。超时抛出 `PrivilegeCommandTimeoutException`。

## 限制 {#limits}

最多同时运行四条命令。此 API 用于非交互式命令，不提供 stdin 或 PTY。
