---
description: 在 Privileged Server 中运行非交互命令，并以流式事件或有界结果消费一次输出。
---

# 命令执行 {#commands}

命令 API 在已连接的 Privileged Server 中启动一个非交互进程。它直接执行参数列表，保持
stdout 和 stderr 分离，不会隐式增加 shell。

## 启动进程 {#start-process}

`Privilege.startCommand` 挂起到远端进程启动成功，然后返回一次性的
`PrivilegeCommandProcess`：

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        arguments = listOf("/system/bin/id"),
        environment = mapOf("LANG" to "C"),
        workingDirectory = "/data/local/tmp",
    ),
)
```

第一个参数是可执行文件。需要 shell 解析时显式传入 shell：

```kotlin
val process = Privilege.startCommand(
    PrivilegeCommand(
        listOf("/system/bin/sh", "-c", "for i in 1 2 3; do echo \$i; sleep 1; done"),
    ),
)
```

## 选择一种消费方式 {#consume-once}

每个进程只能选择一种输出消费方式。只需要进程退出后的输出时使用 `awaitResult()`：

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

默认最多分别返回 1 MiB 的 stdout 和 stderr。超出的数据仍会被排空，保证进程能够结束，
同时对应的 `stdoutTruncated` 或 `stderrTruncated` 会变为 true。可以通过正数
`maxBytesPerStream` 调整限制。

需要实时输出时使用 `stream()`：

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

Flow 只能收集一次。每条输出流内部保持顺序，但字节块不代表一行文本，也不保证 UTF-8
字符边界；展示文本时应使用增量解码器。退出事件始终位于最后，在两条输出 pipe 都到达
EOF 后发出。非零退出码仍是正常退出事件。

## 超时和取消 {#timeout-cancellation}

默认超时为 30 秒，从远端进程启动成功后开始计算，新输出不会重置倒计时。慢速命令可以
传入更大的值，传入 `null` 可禁用截止时间。取消 Flow 收集、取消 `awaitResult`、调用
`cancel()` 或 `close()`、owner 死亡以及服务端关闭都会终止尚未完成的命令。

最多同时运行四个命令，超出后立即失败，不进入等待队列。

## 明确限制 {#limits}

命令 API 不提供 stdin 或 PTY，不支持交互式 shell、终端尺寸、终端信号、ANSI 渲染或
daemon 管理。部分程序在 stdout 连接 pipe 而非终端时会自行缓存，因此即使传输是流式的，
数据仍可能成批到达。
