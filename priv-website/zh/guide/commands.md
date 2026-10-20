---
description: 运行命令并读取输出。
---

# 命令执行 {#commands}

命令 API 在已连接的服务端直接执行参数列表，分别返回 stdout 和 stderr。

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

## 读取输出 {#consume-once}

每个进程只能选择一种输出消费方式。只需要进程退出后的输出时使用 `awaitResult()`：

```kotlin
val result = process.use { it.awaitResult() }
println(result.exitCode)
println(result.stdout.toString(Charsets.UTF_8))
println(result.stderr.toString(Charsets.UTF_8))
```

每条流默认最多保留 1 MiB，超出内容继续排空但不保存，`stdoutTruncated` 或 `stderrTruncated` 标记截断。通过正数 `maxBytesPerStream` 调整上限。

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

默认超时为远端进程启动成功后的 30 秒，截止时间固定。慢速命令可以
传入更大的值，传入 `null` 可禁用截止时间。取消 Flow 收集、取消 `awaitResult`、调用
`cancel()` 或 `close()`、owner 死亡以及服务端关闭都会终止尚未完成的命令。

最多同时运行四个命令，超出后立即失败。

命令执行失败使用 `IOException`。达到配置的超时时限时抛出
`PrivilegeCommandTimeoutException`，它继承 `IOException` 并携带 `timeoutMillis`。
协程取消和 Binder 传输失败保留各自的异常类型。
执行器死亡时，`stream()` 收集或 `awaitResult()` 会直接收到 `DeadObjectException`。
需要明确兜底时，可用 `PrivilegeBinderCall.orElse(...)` 包住操作。

## 限制 {#limits}

不提供 stdin、PTY、交互式 shell 或 daemon 管理。程序可能对管道输出自行缓冲，因此流式数据也可能成批到达。
